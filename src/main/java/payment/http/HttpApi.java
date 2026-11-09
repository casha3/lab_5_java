package payment.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import payment.config.AppConfig;
import payment.error.ErrorCode;
import payment.error.PaymentException;
import payment.jaxb.JaxbSupport;
import payment.model.NotificationType;
import payment.service.CommitResult;
import payment.service.PaymentService;
import payment.service.ReverseResult;
import payment.money.Money;

/** REST-слой на Vert.x Web. Обработчики — лямбды/ссылки на методы. */
public class HttpApi {

  private static final Logger log = LoggerFactory.getLogger(HttpApi.class);
  private static final Pattern BIC_PATTERN = Pattern.compile("^[A-Z0-9]{8}([A-Z0-9]{3})?$");
  private static final String API = "/api";

  private final Vertx vertx;
  private final PaymentService service;
  private final JaxbSupport jaxb;
  private final AppConfig config;
  private final ObjectMapper objectMapper;

  public HttpApi(Vertx vertx, PaymentService service, JaxbSupport jaxb, AppConfig config) {
    this.vertx = vertx;
    this.service = service;
    this.jaxb = jaxb;
    this.config = config;
    this.objectMapper =
        new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  /** Регистрирует маршруты на роутере. */
  public Router mounts() {
    Router router = Router.router(vertx);

    // Читает тело запроса в rc.body() ДО обработчиков (иначе body() = null).
    router.route().handler(BodyHandler.create());

    router.get(API + "/health").handler(this::handleHealth);

    router.post(API + "/banks").handler(this::handleCreateBank);
    router.get(API + "/banks").handler(this::handleListBanks);
    router.get(API + "/banks/:bic").handler(this::handleGetBank);

    router.post(API + "/transactions").handler(this::handleCommitTransaction);
    router.get(API + "/transactions/:id").handler(this::handleGetTransaction);
    router.get(API + "/transactions/:id/notifications/:type").handler(this::handleGetNotification);
    router.post(API + "/transactions/:id/reversal").handler(this::handleReversal);

    return router;
  }

  // Лямбда, а не анонимный класс: Handler<T> — «функциональный интерфейс» с
  // единственным абстрактным методом, поэтому лямбда/ссылка на метод достаточна.
  // Вернуться к классу пришлось бы при необходимости состояния между вызовами
  // (например, счётчик/закрываемый ресурс на обработчик), втором методе или
  // собственном this в замыкании.
  private void handleHealth(RoutingContext rc) {
    writeJson(rc, 200, Map.of("status", "ok"));
  }

  private void handleCreateBank(RoutingContext rc) {
    String requestId = UUID.randomUUID().toString();
    String contentType = rc.request().getHeader("Content-Type");
    if (contentType == null || !contentType.startsWith("application/json")) {
      sendError(rc, requestId, ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Ожидается application/json");
      return;
    }
    BankRequest request;
    try {
      request = objectMapper.readValue(rc.body().asString(), BankRequest.class);
    } catch (JsonProcessingException | RuntimeException e) {
      sendError(rc, requestId, ErrorCode.INVALID_REQUEST, "Некорректный JSON");
      return;
    }
    if (!BIC_PATTERN.matcher(request.bic).matches()) {
      sendError(rc, requestId, ErrorCode.INVALID_REQUEST, "Некорректный BIC");
      return;
    }
    if (request.name == null || request.name.trim().isEmpty() || request.name.trim().length() > 128) {
      sendError(rc, requestId, ErrorCode.INVALID_REQUEST, "Некорректное название");
      return;
    }
    if (request.balance == null || !Money.isValid(request.balance)) {
      sendError(rc, requestId, ErrorCode.INVALID_REQUEST, "Некорректный баланс");
      return;
    }
    runAsync(
        rc,
        requestId,
        () -> service.createBank(request.bic, request.name.trim(), request.balance),
        bank -> {
          log.info("Создан банк {} на потоке {}", bank.bic(), Thread.currentThread().getName());
          writeJson(rc, 201, bank);
        });
  }

  private void handleListBanks(RoutingContext rc) {
    String requestId = UUID.randomUUID().toString();
    runAsync(rc, requestId, service::listBanks, banks -> writeJson(rc, 200, banks));
  }

  private void handleGetBank(RoutingContext rc) {
    String requestId = UUID.randomUUID().toString();
    String bic = rc.pathParam("bic");
    runAsync(
        rc,
        requestId,
        () -> service.findBank(bic),
        bank -> {
          if (bank.isPresent()) {
            writeJson(rc, 200, bank.get());
          } else {
            sendError(rc, requestId, ErrorCode.BANK_NOT_FOUND, "Банк не найден");
          }
        });
  }

  private void handleCommitTransaction(RoutingContext rc) {
    String requestId = UUID.randomUUID().toString();
    String contentType = rc.request().getHeader("Content-Type");
    if (contentType == null || !contentType.startsWith("application/xml")) {
      sendError(rc, requestId, ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Ожидается application/xml");
      return;
    }
    String xml = rc.body().asString();
    runAsync(
        rc,
        requestId,
        () -> service.commit(jaxb.unmarshalPaymentRequest(xml)),
        result -> writeJson(rc, 201, commitBody(result)));
  }

  private void handleGetTransaction(RoutingContext rc) {
    String requestId = UUID.randomUUID().toString();
    UUID id = parseUuid(rc, requestId, rc.pathParam("id"));
    if (id == null) {
      return;
    }
    runAsync(
        rc,
        requestId,
        () -> service.findTransaction(id),
        txn -> {
          if (txn.isPresent()) {
            writeJson(rc, 200, txn.get());
          } else {
            sendError(rc, requestId, ErrorCode.TRANSACTION_NOT_FOUND, "Транзакция не найдена");
          }
        });
  }

  private void handleGetNotification(RoutingContext rc) {
    String requestId = UUID.randomUUID().toString();
    UUID id = parseUuid(rc, requestId, rc.pathParam("id"));
    if (id == null) {
      return;
    }
    NotificationType type;
    try {
      type = NotificationType.valueOf(rc.pathParam("type"));
    } catch (IllegalArgumentException e) {
      sendError(rc, requestId, ErrorCode.INVALID_REQUEST, "Недопустимый тип уведомления");
      return;
    }
    runAsync(
        rc,
        requestId,
        () -> service.notificationPayload(id, type),
        payload -> {
          HttpServerResponse response = rc.response();
          response.putHeader("Content-Type", "application/xml");
          if (!response.ended()) {
            response.setStatusCode(200).end(payload);
          }
        });
  }

  private void handleReversal(RoutingContext rc) {
    String requestId = UUID.randomUUID().toString();
    UUID id = parseUuid(rc, requestId, rc.pathParam("id"));
    if (id == null) {
      return;
    }
    runAsync(
        rc,
        requestId,
        () -> service.reverse(id),
        result -> writeJson(rc, 200, reverseBody(result)));
  }

  private UUID parseUuid(RoutingContext rc, String requestId, String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      sendError(rc, requestId, ErrorCode.INVALID_REQUEST, "Некорректный UUID");
      return null;
    }
  }

  private Map<String, Object> commitBody(CommitResult result) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("transactionId", result.transactionId().toString());
    body.put("status", result.status().name());
    body.put("amount", result.amount());
    body.put("commissionAmount", result.commissionAmount());
    return body;
  }

  private Map<String, Object> reverseBody(ReverseResult result) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("transactionId", result.transactionId().toString());
    body.put("status", result.status().name());
    return body;
  }

  /**
   * Выполняет блокирующую DB-операцию вне event loop (общий worker-пул Vert.x,
   * а не новый executor на каждый запрос). После выполнения отвечает клиенту.
   */
  private <T> void runAsync(
      RoutingContext rc, String requestId, Task<T> task, Consumer<T> onSuccess) {
    Future<T> future =
        vertx.executeBlocking(
            (Promise<T> promise) -> {
              log.info("DB/блокирующая работа requestId={} на потоке {}", requestId,
                  Thread.currentThread().getName());
              try {
                long delay = config.dbDelayMillis();
                if (delay > 0) {
                  Thread.sleep(delay);
                }
                promise.complete(task.run());
              } catch (Exception e) {
                promise.fail(e);
              }
            },
            false);
    future.onSuccess(res -> onSuccess.accept(res))
        .onFailure(t -> sendError(rc, requestId, t));
  }

  private void sendError(RoutingContext rc, String requestId, Throwable throwable) {
    if (throwable instanceof PaymentException pe) {
      sendError(rc, requestId, pe.code(), pe.getMessage());
    } else {
      log.error("Внутренняя ошибка requestId={}", requestId, throwable);
      sendError(rc, requestId, ErrorCode.INTERNAL_ERROR, "Внутренняя ошибка");
    }
  }

  private void sendError(RoutingContext rc, String requestId, ErrorCode code, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code.name());
    body.put("message", message);
    body.put("requestId", requestId);
    if (code != ErrorCode.BANK_EXISTS
        && code != ErrorCode.INSUFFICIENT_FUNDS
        && code != ErrorCode.REVERSAL_INSUFFICIENT_FUNDS
        && code != ErrorCode.REVERSAL_ALREADY_APPLIED
        && code != ErrorCode.BALANCE_LIMIT_EXCEEDED) {
      log.warn("Ошибка requestId={} code={} message={}", requestId, code.name(), message);
    }
    writeJson(rc, code.status(), body);
  }

  private void writeJson(RoutingContext rc, int status, Object body) {
    String json;
    try {
      json = objectMapper.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      json = "{\"error\":\"INTERNAL_ERROR\"}";
    }
    HttpServerResponse response = rc.response();
    response.putHeader("Content-Type", "application/json");
    if (!response.ended()) {
      response.setStatusCode(status).end(json);
    }
  }

  /** Тело запроса создания банка. */
  record BankRequest(String bic, String name, BigDecimal balance) {}

  @FunctionalInterface
  interface Task<T> {
    T run() throws Exception;
  }
}
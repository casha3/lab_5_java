package payment.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import jakarta.xml.bind.JAXBException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import payment.config.AppConfig;
import payment.jaxb.JaxbSupport;
import payment.jooq.Database;
import payment.repo.BankRepository;
import payment.repo.NotificationRepository;
import payment.repo.PaymentRepository;
import payment.service.PaymentService;

/**
 * Тест конкурентности требований Vert.x: DB-путь выполняется на worker-потоке,
 * поэтому искусственная задержка DB-пути не должна блокировать независимый
 * HTTP-запрос без БД (здесь — /api/health).
 */
class ConcurrencyBlockingTest {

  private static final int DELAY_MILLIS = 2000;

  private static Vertx vertx;
  private static HttpServer server;
  private static Database db;
  private static String base;

  @BeforeAll
  static void startServer() throws Exception {
    String url = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/payments_db_test");
    String user = System.getenv().getOrDefault("TEST_DB_USER", System.getProperty("user.name"));
    String pass = System.getenv().getOrDefault("TEST_DB_PASSWORD", "");
    AppConfig config = new AppConfig(url, user, pass, 0, new BigDecimal("1.50"), DELAY_MILLIS);
    db = new Database(config);
    JaxbSupport jaxb = new JaxbSupport();
    PaymentService service =
        new PaymentService(
            db,
            new BankRepository(),
            new PaymentRepository(),
            new NotificationRepository(),
            jaxb,
            config);

    vertx = Vertx.vertx();
    HttpApi httpApi = new HttpApi(vertx, service, jaxb, config);
    CompletableFuture<Integer> portFuture = new CompletableFuture<>();
    server =
        vertx
            .createHttpServer()
            .requestHandler(httpApi.mounts())
            .listen(
                0,
                result -> {
                  if (result.succeeded()) {
                    portFuture.complete(result.result().actualPort());
                  } else {
                    portFuture.completeExceptionally(result.cause());
                  }
                });
    base = "http://localhost:" + portFuture.get();
  }

  @AfterAll
  static void stopServer() {
    try {
      if (server != null) {
        server.close().toCompletionStage().toCompletableFuture().join();
      }
    } finally {
      if (vertx != null) {
        vertx.close().toCompletionStage().toCompletableFuture().join();
      }
      if (db != null) {
        db.close();
      }
    }
  }

  @Test
  void dbDelayDoesNotBlockIndependentRequest() throws Exception {
    HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();

    // Блокирующий DB-запрос (GET /api/banks) уснёт на DELAY_MILLIS в worker-потоке.
    CompletableFuture<Void> slow =
        CompletableFuture.runAsync(
            () -> {
              try {
                client.send(
                        HttpRequest.newBuilder()
                            .uri(URI.create(base + "/api/banks"))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString())
                    .statusCode();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    // Пока DB-запрос спит, независимый /api/health должен ответить сразу.
    long start = System.currentTimeMillis();
    HttpResponse<String> health =
        client.send(
            HttpRequest.newBuilder().uri(URI.create(base + "/api/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    long elapsed = System.currentTimeMillis() - start;

    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(elapsed).isLessThan(DELAY_MILLIS - 500);

    slow.join();
  }
}
package main;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import jakarta.xml.bind.JAXBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import payment.config.AppConfig;
import payment.http.HttpApi;
import payment.jaxb.JaxbSupport;
import payment.jooq.Database;
import payment.repo.BankRepository;
import payment.repo.NotificationRepository;
import payment.repo.PaymentRepository;
import payment.service.PaymentService;

/** Точка входа платёжной системы (Vert.x Web + jOOQ + PostgreSQL). */
public final class Main {

  private static final Logger log = LoggerFactory.getLogger(Main.class);

  private Main() {}

  /** Запускает HTTP-сервер. */
  public static void main(String[] args) {
    AppConfig config = AppConfig.fromEnv();
    log.info("HTTP_PORT={} DB_URL={} COMMISSION_RATE={}",
        config.httpPort(), config.dbUrl(), config.commissionRate());

    JaxbSupport jaxb;
    try {
      jaxb = new JaxbSupport();
    } catch (JAXBException e) {
      throw new IllegalStateException("Не удалось инициализировать JAXB", e);
    }

    Database database = new Database(config);
    PaymentService service =
        new PaymentService(
            database,
            new BankRepository(),
            new PaymentRepository(),
            new NotificationRepository(),
            jaxb,
            config);

    Vertx vertx = Vertx.vertx(new VertxOptions().setWorkerPoolSize(20));
    HttpApi httpApi = new HttpApi(vertx, service, jaxb, config);
    vertx.createHttpServer()
        .requestHandler(httpApi.mounts())
        .listen(
            config.httpPort(),
            result -> {
              if (result.succeeded()) {
                log.info("Сервер запущен на порту {}", config.httpPort());
              } else {
                log.error("Не удалось запустить сервер", result.cause());
              }
            });
  }
}
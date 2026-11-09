package payment.service;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.xml.bind.JAXBException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import payment.config.AppConfig;
import payment.jaxb.JaxbSupport;
import payment.jooq.Database;
import payment.repo.BankRepository;
import payment.repo.NotificationRepository;
import payment.repo.PaymentRepository;

/** База интеграционных тестов: БД payments_db_test, инфраструктура jOOQ + сервис. */
abstract class AbstractDbTest {

  protected static Database db;
  protected static PaymentService service;

  @BeforeAll
  static void initInfrastructure() throws JAXBException {
    String url = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/payments_db_test");
    String user = System.getenv().getOrDefault("TEST_DB_USER", System.getProperty("user.name"));
    String pass = System.getenv().getOrDefault("TEST_DB_PASSWORD", "");
    AppConfig config = new AppConfig(url, user, pass, 0, new BigDecimal("1.50"), 0);
    db = new Database(config);
    JaxbSupport jaxb = new JaxbSupport();
    service =
        new PaymentService(
            db,
            new BankRepository(),
            new PaymentRepository(),
            new NotificationRepository(),
            jaxb,
            config);
  }

  @AfterAll
  static void closeInfrastructure() {
    db.close();
  }

  /** Генерирует практически уникальный 11-символьный валидный BIC. */
  protected static String bic() {
    return "TST" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
  }

  protected static payment.jaxb.generated.PaymentRequest request(
      String issuer, String receiver, String acquirer, String amount) {
    payment.jaxb.generated.PaymentRequest request = new payment.jaxb.generated.PaymentRequest();
    request.setClientReference("ref-" + UUID.randomUUID());
    request.setIssuerBic(issuer);
    request.setReceiverBic(receiver);
    request.setAcquirerBic(acquirer);
    request.setAmount(new BigDecimal(amount));
    return request;
  }
}
package payment.jaxb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.GregorianCalendar;
import javax.xml.datatype.DatatypeFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import payment.error.ErrorCode;
import payment.error.PaymentException;
import payment.jaxb.generated.BankNotification;
import payment.jaxb.generated.PaymentRequest;

/** Юнит-тесты JAXB: безопасный разбор, валидация по XSD, маршалинг уведомлений. */
class JaxbSupportTest {

  private static JaxbSupport jaxb;
  private static DatatypeFactory datatypeFactory;

  @BeforeAll
  static void setUp() throws Exception {
    jaxb = new JaxbSupport();
    datatypeFactory = DatatypeFactory.newInstance();
  }

  private static PaymentRequest request() throws Exception {
    String xml =
        "<pr:PaymentRequest xmlns:pr=\"urn:payment:request:v1\">"
            + "<pr:clientReference>ref-1</pr:clientReference>"
            + "<pr:issuerBic>BICIS001</pr:issuerBic>"
            + "<pr:receiverBic>BICRV001</pr:receiverBic>"
            + "<pr:acquirerBic>BICAQ001</pr:acquirerBic>"
            + "<pr:amount>1200.00</pr:amount>"
            + "</pr:PaymentRequest>";
    return jaxb.unmarshalPaymentRequest(xml);
  }

  @Test
  void validRequestParsesAndValidates() throws Exception {
    PaymentRequest request = request();
    assertThat(request.getIssuerBic()).isEqualTo("BICIS001");
    assertThat(request.getClientReference()).isEqualTo("ref-1");
    assertThat(request.getAmount().compareTo(new java.math.BigDecimal("1200.00"))).isEqualTo(0);
  }

  @Test
  void schemaViolationRejected() {
    String xml =
        "<pr:PaymentRequest xmlns:pr=\"urn:payment:request:v1\">"
            + "<pr:clientReference>ref</pr:clientReference>"
            + "<pr:issuerBic>TOO LONG BIC 123</pr:issuerBic>"
            + "<pr:receiverBic>OTHER01</pr:receiverBic>"
            + "<pr:acquirerBic>ACQD000</pr:acquirerBic>"
            + "<pr:amount>100.00</pr:amount>"
            + "</pr:PaymentRequest>";
    assertThatThrownBy(() -> jaxb.unmarshalPaymentRequest(xml))
        .isInstanceOf(PaymentException.class)
        .extracting(e -> ((PaymentException) e).code())
        .isEqualTo(ErrorCode.SCHEMA_VALIDATION_ERROR);
  }

  @Test
  void malformedXmlRejected() {
    assertThatThrownBy(() -> jaxb.unmarshalPaymentRequest("<broken"))
        .isInstanceOf(PaymentException.class)
        .extracting(e -> ((PaymentException) e).code())
        .isEqualTo(ErrorCode.MALFORMED_XML);
  }

  @Test
  void doctypeRejectedAsSecurityError() {
    String xml =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE PaymentRequest [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<pr:PaymentRequest xmlns:pr=\"urn:payment:request:v1\"/>";
    assertThatThrownBy(() -> jaxb.unmarshalPaymentRequest(xml))
        .isInstanceOf(PaymentException.class)
        .extracting(e -> ((PaymentException) e).code())
        .isEqualTo(ErrorCode.XML_SECURITY_ERROR);
  }

  @Test
  void externalEntityRejectedAsSecurityError() {
    String xml =
        "<pr:PaymentRequest xmlns:pr=\"urn:payment:request:v1\" "
            + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
            + "xsi:noNamespaceSchemaLocation=\"http://evil.example/schema.xsd\" "
            + "><!ENTITY % ext SYSTEM \"file:///tmp/x\">";
    assertThatThrownBy(() -> jaxb.unmarshalPaymentRequest(xml))
        .isInstanceOf(PaymentException.class)
        .extracting(e -> ((PaymentException) e).code())
        .isEqualTo(ErrorCode.XML_SECURITY_ERROR);
  }

  @Test
  void notificationMarshalProducesValidXsdDocument() throws Exception {
    BankNotification n = new BankNotification();
    n.setNotificationId("11111111-1111-1111-1111-111111111111");
    n.setTransactionId("22222222-2222-2222-2222-222222222222");
    n.setType(payment.jaxb.generated.NotificationType.DEBIT_REQUEST);
    n.setBankBic("ISSUER01");
    n.setAmount(new java.math.BigDecimal("1200.00"));
    n.setCreatedAt(
        datatypeFactory.newXMLGregorianCalendar(
            GregorianCalendar.from(OffsetDateTime.now().toZonedDateTime())));

    String payload = jaxb.marshalNotification(n);
    // Валидация уже выполняется внутри marshal; дополнительно проверяем содержимое.
    assertThat(payload).contains("DEBIT_REQUEST").contains("ISSUER01").contains("urn:payment:notification:v1");
  }
}
package payment.jaxb;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import javax.xml.XMLConstants;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import payment.error.ErrorCode;
import payment.error.PaymentException;
import payment.jaxb.generated.PaymentRequest;

/**
 * Поддержка JAXB: генерация классов из XSD (xjc) уже выполнена в Maven build;
 * здесь — безопасный unmarshal входящего запроса и marshal уведомлений с
 * валидацией только по доверенным схемам приложения.
 */
public final class JaxbSupport {

  private final JAXBContext context;
  private final Schema paymentRequestSchema;
  private final Schema notificationSchema;

  public JaxbSupport() throws JAXBException {
    this.context = JAXBContext.newInstance("payment.jaxb.generated");
    this.paymentRequestSchema = buildSchema("/xsd/payment-request.xsd");
    this.notificationSchema = buildSchema("/xsd/bank-notification.xsd");
  }

  private static Schema buildSchema(String resource) {
    SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    try {
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    } catch (SAXException e) {
      throw new IllegalStateException("Не удалось настроить SchemaFactory", e);
    }
    try (InputStream in = JaxbSupport.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("XSD не найден в classpath: " + resource);
      }
      return factory.newSchema(new StreamSource(in));
    } catch (IOException | SAXException e) {
      throw new IllegalStateException("Не удалось загрузить схему: " + resource, e);
    }
  }

  /**
   * Безопасно разбирает и валидирует входящий платёжный запрос по
   * payment-request.xsd. DOCTYPE/внешние сущности отклоняются до бизнес-операции.
   *
   * @param xml входящий XML
   * @return сгенерированный JAXB-объект запроса
   */
  public PaymentRequest unmarshalPaymentRequest(String xml) {
    if (XmlSecurity.containsDangerousDecl(xml)) {
      throw new PaymentException(ErrorCode.XML_SECURITY_ERROR, "Запрещённые декларации DOCTYPE/ENTITY");
    }
    Document document;
    try {
      document = XmlSecurity.parse(xml);
    } catch (SAXException e) {
      throw new PaymentException(ErrorCode.MALFORMED_XML, "Некорректный XML", e);
    } catch (IOException e) {
      throw new PaymentException(ErrorCode.MALFORMED_XML, "Ошибка чтения XML", e);
    }
    try {
      paymentRequestSchema.newValidator().validate(new DOMSource(document));
    } catch (SAXException e) {
      throw new PaymentException(ErrorCode.SCHEMA_VALIDATION_ERROR, "XML не прошёл валидацию по схеме", e);
    } catch (IOException e) {
      throw new PaymentException(ErrorCode.MALFORMED_XML, "Ошибка валидации XML", e);
    }
    try {
      Unmarshaller unmarshaller = context.createUnmarshaller();
      return (PaymentRequest) unmarshaller.unmarshal(document);
    } catch (JAXBException e) {
      throw new PaymentException(ErrorCode.MALFORMED_XML, "Не удалось разобрать XML", e);
    }
  }

  /**
   * Маршалит объект JAXB-модели в строку XML и валидирует результат по
   * bank-notification.xsd.
   *
   * @param object сгенерированный объект уведомления
   * @return строка XML
   */
  public String marshalNotification(Object object) {
    try {
      Marshaller marshaller = context.createMarshaller();
      marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
      StringWriter writer = new StringWriter();
      marshaller.marshal(object, writer);
      String xml = writer.toString();
      notificationSchema.newValidator().validate(new StreamSource(new StringReader(xml)));
      return xml;
    } catch (JAXBException | SAXException | IOException e) {
      throw new PaymentException(ErrorCode.INTERNAL_ERROR, "Не удалось сформировать уведомление", e);
    }
  }
}
package payment.jaxb;

import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Безопасный разбор XML. Запрещает DOCTYPE и внешние сущности (XXE),
 * внешние DTD/схемы, указанные клиентом. Валидация выполняется только по
 * доверенным схемам приложения.
 */
final class XmlSecurity {

  private XmlSecurity() {}

  /**
   * Грубая проверка на запрещённые декларации DOCTYPE / ENTITY.
   *
   * @param xml входной XML
   * @return true, если найдена запрещённая декларация
   */
  static boolean containsDangerousDecl(String xml) {
    if (xml == null) {
      return false;
    }
    String upper = xml.toUpperCase(Locale.ROOT);
    return upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY");
  }

  /**
   * Разбирает XML в документ с защищённой фабрикой (запрет DOCTYPE, внешних
   * сущностей, XInclude).
   *
   * @param xml входной XML
   * @return документ
   * @throws SAXException при некорректном или небезопасном XML
   * @throws IOException при ошибке чтения
   */
  static Document parse(String xml) throws SAXException, IOException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
    setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
    setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
    try {
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    } catch (IllegalArgumentException ignored) {
      // атрибут может не поддерживаться фабрикой — ограничения уже заданы флагами
    }
    DocumentBuilder builder;
    try {
      builder = factory.newDocumentBuilder();
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException("Не удалось настроить безопасный парсер", e);
    }
    return builder.parse(new InputSource(new StringReader(xml)));
  }

  private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
    try {
      factory.setFeature(name, value);
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException("Парсер не поддерживает защитный флаг: " + name, e);
    }
  }
}
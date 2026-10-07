package org.zaproxy.zap.extension.prettyview.formatters;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class XmlPrettifier
implements PrettyPrettifier {
  private static final String INDENT_AMOUNT = Integer.toString(PrettyPrettifier.INDENT_WIDTH);
  private static final Pattern ENTITY_DECLARATION = Pattern.compile("<!\\s*ENTITY", 2);

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.XML;
  }

  @Override
  public String prettify(String body) throws PrettificationException {
    if (body == null || body.trim().isEmpty()) {
      throw new PrettificationException("Empty XML payload");
    }
    if (ENTITY_DECLARATION.matcher(body).find()) {
      throw new PrettificationException("XML declares internal entities; rendered verbatim to avoid resolving them");
    }
    Document document = XmlPrettifier.parse(body);
    String formatted = XmlPrettifier.transform(document);
    if (formatted == null || formatted.trim().isEmpty()) {
      throw new PrettificationException("XML transformation produced no output");
    }
    return formatted;
  }

  private static Document parse(String body) throws PrettificationException {
    try {
      DocumentBuilder builder = XmlPrettifier.newDocumentBuilder();
      return builder.parse(new InputSource(new StringReader(body)));
    }
    catch (IOException | IllegalArgumentException | ParserConfigurationException | SAXException e) {
      throw new PrettificationException("Malformed XML: " + XmlPrettifier.describe(e), e);
    }
  }

  private static DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setExpandEntityReferences(false);
    factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
    factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "");
    return factory.newDocumentBuilder();
  }

  private static String transform(Document document) throws PrettificationException {
    try {
      TransformerFactory factory = TransformerFactory.newInstance();
      factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
      factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
      factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "");
      Transformer transformer = factory.newTransformer();
      transformer.setOutputProperty("indent", "yes");
      transformer.setOutputProperty("encoding", "UTF-8");
      transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", INDENT_AMOUNT);
      transformer.setOutputProperty("omit-xml-declaration", "yes");
      StringWriter writer = new StringWriter(1024);
      transformer.transform(new DOMSource(document), new StreamResult(writer));
      return writer.toString();
    }
    catch (IllegalArgumentException | TransformerException e) {
      throw new PrettificationException("Unable to format XML: " + XmlPrettifier.describe(e), e);
    }
  }

  private static String describe(Exception e) {
    String message = e.getMessage();
    return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
  }
}


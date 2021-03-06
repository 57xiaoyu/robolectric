package org.robolectric.res;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public abstract class XmlLoader {
  private static final DocumentBuilderFactory documentBuilderFactory;
  static {
    documentBuilderFactory = DocumentBuilderFactory.newInstance();
    documentBuilderFactory.setNamespaceAware(true);
    documentBuilderFactory.setIgnoringComments(true);
    documentBuilderFactory.setIgnoringElementContentWhitespace(true);
  }

  private DocumentBuilder documentBuilder;

  synchronized public Document parse(FsFile xmlFile) {
    InputStream inputStream = null;
    try {
      if (documentBuilder == null) {
        documentBuilder = documentBuilderFactory.newDocumentBuilder();
      }
      inputStream = xmlFile.getInputStream();
      return documentBuilder.parse(inputStream);
    } catch (ParserConfigurationException | IOException | SAXException e) {
      throw new RuntimeException(e);
    } finally {
      if (inputStream != null) try {
        inputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  protected void processResourceXml(FsFile xmlFile, Node node, String packageName) throws Exception {
    processResourceXml(xmlFile, new XpathResourceXmlLoader.XmlNode(node), new XmlContext(packageName, xmlFile));
  }

  protected abstract void processResourceXml(FsFile xmlFile, XpathResourceXmlLoader.XmlNode xmlNode, XmlContext xmlContext) throws Exception;

  public static class XmlContext {
    public static final Pattern DIR_QUALIFIER_PATTERN = Pattern.compile("^[^-]+(?:-(.*))?$");

    public final String packageName;
    private final FsFile xmlFile;

    public XmlContext(String packageName, FsFile xmlFile) {
      this.packageName = packageName;
      this.xmlFile = xmlFile;
    }

    public String getDirPrefix() {
      String parentDir = xmlFile.getParent().getName();
      return parentDir.split("-")[0];
    }

    public String getQualifiers() {
      String parentDir = xmlFile.getParent().getName();
      Matcher matcher = DIR_QUALIFIER_PATTERN.matcher(parentDir);
      if (!matcher.find()) throw new IllegalStateException(parentDir);
      return matcher.group(1);
    }

    public FsFile getXmlFile() {
      return xmlFile;
    }

    @Override public String toString() {
      return "XmlContext{" +
          "packageName='" + packageName + '\'' +
          ", xmlFile=" + xmlFile +
          '}';
    }
  }
}

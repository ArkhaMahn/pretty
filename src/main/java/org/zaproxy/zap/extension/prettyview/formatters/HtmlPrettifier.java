package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.Node;

public class HtmlPrettifier
implements PrettyPrettifier {
  private static final int INDENT_AMOUNT = PrettyPrettifier.INDENT_WIDTH;
  private static final Pattern EMBEDDED_TAG = Pattern.compile("<(/?)(script|style|pre|textarea|template)([^>]*?)(/?)>", Pattern.CASE_INSENSITIVE);
  private static final Pattern CLOSING_TAG = Pattern.compile("</\\s*(script|style)\\s*>", Pattern.CASE_INSENSITIVE);
  private final JavaScriptPrettifier jsPrettifier = new JavaScriptPrettifier();
  private final CssPrettifier cssPrettifier = new CssPrettifier();

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.HTML;
  }

  @Override
  public String prettify(String body) throws PrettificationException {
    if (body == null || body.trim().isEmpty()) {
      throw new PrettificationException("Empty HTML payload");
    }
    Document document = HtmlPrettifier.parse(body);
    this.formatEmbeddedCode(document);
    document.outputSettings().prettyPrint(true).indentAmount(INDENT_AMOUNT).escapeMode(Entities.EscapeMode.base).maxPaddingWidth(-1);
    return HtmlPrettifier.indentEmbeddedBlocks(document.outerHtml(), INDENT_AMOUNT);
  }

  private static Document parse(String body) throws PrettificationException {
    try {
      return HtmlPrettifier.isFullDocument(body) ? Jsoup.parse((String)body) : Jsoup.parseBodyFragment((String)body);
    }
    catch (RuntimeException e) {
      throw new PrettificationException("Unable to parse HTML: " + HtmlPrettifier.describe(e), e);
    }
  }

  private static boolean isFullDocument(String body) {
    String head = body.length() > 2048 ? body.substring(0, 2048) : body;
    String lower = head.toLowerCase(Locale.ROOT);
    return lower.contains("<html") || lower.contains("<!doctype html");
  }

  private void formatEmbeddedCode(Document document) {
    for (Element script : document.select("script")) {
      if (!HtmlPrettifier.isInline(script) || HtmlPrettifier.isWhitespaceSensitive(script)) continue;
      HtmlPrettifier.rewriteData(script, HtmlPrettifier.blockBody(this.jsPrettifier.prettify(script.data())));
    }
    for (Element style : document.select("style")) {
      if (HtmlPrettifier.isWhitespaceSensitive(style)) continue;
      HtmlPrettifier.rewriteData(style, HtmlPrettifier.blockBody(this.cssPrettifier.prettify(style.data())));
    }
  }

  /**
   * Multi-line embedded code is put on its own line so it can be indented under its tag; a
   * single-line body is left inline with the tag.
   */
  private static String blockBody(String content) {
    if (content == null) {
      return null;
    }
    String trimmed = content.strip();
    return trimmed.indexOf('\n') < 0 ? trimmed : "\n" + trimmed;
  }

  /**
   * jsoup emits the body of a {@code script} or {@code style} element verbatim and never indents it,
   * so multi-line embedded code ends up at column 0 with its closing tag stranded below. This shifts
   * those lines one level in from the opening tag and aligns the closing tag back with it. Anything
   * inside {@code pre}, {@code textarea} or {@code template} is whitespace-sensitive and left byte
   * for byte as it was.
   */
  private static String indentEmbeddedBlocks(String html, int width) {
    StringBuilder out = new StringBuilder(html.length() + 64);
    String[] lines = html.split("\n", -1);
    int embeddedAt = -1;
    int preserved = 0;
    for (int i = 0; i < lines.length; ++i) {
      String line = lines[i];
      int column = line.length() - line.stripLeading().length();
      if (embeddedAt < 0) {
        out.append(line);
        preserved = HtmlPrettifier.trackPreserved(preserved, line);
        embeddedAt = HtmlPrettifier.openingEmbeddedColumn(preserved, column, line);
      }
      else {
        String trimmed = line.stripLeading();
        if (trimmed.isEmpty()) {
          out.append(trimmed);
        }
        else {
          Matcher close = HtmlPrettifier.CLOSING_TAG.matcher(trimmed);
          if (!close.find()) {
            // Keep the embedded code's own relative indentation, one level in from its tag.
            out.append(" ".repeat(embeddedAt + width)).append(line);
          }
          else if (close.start() == 0) {
            out.append(" ".repeat(embeddedAt)).append(trimmed);
          }
          else {
            int cut = line.length() - trimmed.length() + close.start();
            out.append(" ".repeat(embeddedAt + width)).append(line, 0, cut);
            out.append('\n').append(" ".repeat(embeddedAt)).append(line, cut, line.length());
            embeddedAt = -1;
          }
        }
      }
      if (i < lines.length - 1) {
        out.append('\n');
      }
    }
    return out.toString();
  }

  private static int trackPreserved(int preserved, String line) {
    Matcher matcher = HtmlPrettifier.EMBEDDED_TAG.matcher(line);
    while (matcher.find()) {
      String tag = matcher.group(2).toLowerCase(Locale.ROOT);
      if ("script".equals(tag) || "style".equals(tag)) {
        continue;
      }
      if (!matcher.group(1).isEmpty()) {
        preserved = Math.max(0, preserved - 1);
      }
      else if (!"/".equals(matcher.group(4))) {
        ++preserved;
      }
    }
    return preserved;
  }

  /** The column of an embedded block opened by this line, or -1 if the line opens none. */
  private static int openingEmbeddedColumn(int preserved, int column, String line) {
    if (preserved != 0) {
      return -1;
    }
    Matcher matcher = HtmlPrettifier.EMBEDDED_TAG.matcher(line);
    while (matcher.find()) {
      String tag = matcher.group(2).toLowerCase(Locale.ROOT);
      if (!"script".equals(tag) && !"style".equals(tag)) {
        continue;
      }
      if (matcher.group(1).isEmpty() && !"/".equals(matcher.group(4)) && matcher.end() == line.length()) {
        return column;
      }
    }
    return -1;
  }

  private static boolean isInline(Element script) {
    if (!script.attributes().hasKey("src")) {
      return true;
    }
    String type = script.attr("type").trim().toLowerCase(Locale.ROOT);
    return type.isEmpty() || "text/javascript".equals(type) || "application/javascript".equals(type) || "module".equals(type) || "text/ecmascript".equals(type);
  }

  private static void rewriteData(Element element, String content) {
    if (content == null || content.isEmpty()) {
      return;
    }
    List<DataNode> dataNodes = element.dataNodes();
    if (dataNodes.isEmpty()) {
      return;
    }
    dataNodes.get(0).setWholeData(content);
    for (int i = 1; i < dataNodes.size(); ++i) {
      dataNodes.get(i).remove();
    }
  }

  static boolean isWhitespaceSensitive(Node node) {
    for (Node parent = node.parent(); parent != null; parent = parent.parent()) {
      String tag;
      if (!(parent instanceof Element) || !"pre".equals(tag = ((Element)parent).tagName()) && !"textarea".equals(tag) && !"template".equals(tag)) continue;
      return true;
    }
    return false;
  }

  private static String describe(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
  }
}


package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.ArrayDeque;
import java.util.Deque;
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
  private static final Pattern DOCTYPE_DECLARATION = Pattern.compile("<!doctype[^>]*>", Pattern.CASE_INSENSITIVE);
  /**
   * Deepest element nesting that is still pretty-printed. jsoup indents every level, so tens of
   * thousands of nested tags unfold into hundreds of megabytes of padding; past this the document
   * goes back verbatim. Real pages nest far shallower.
   */
  private static final int MAX_ELEMENT_DEPTH = 256;
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
    if (HtmlPrettifier.elementDepth(document) > MAX_ELEMENT_DEPTH) {
      throw new PrettificationException("Markup nests too deeply; rendered verbatim");
    }
    this.formatEmbeddedCode(document);
    document.outputSettings().prettyPrint(true).indentAmount(INDENT_AMOUNT).escapeMode(Entities.EscapeMode.base).maxPaddingWidth(-1);
    return HtmlPrettifier.restoreDoctype(body,
        HtmlPrettifier.indentEmbeddedBlocks(document.outerHtml(), INDENT_AMOUNT));
  }

  /**
   * Puts the input's own doctype declaration back onto the output. jsoup lowercases it on the way
   * through (so {@code <!DOCTYPE html>} comes back {@code <!doctype html>}), which is harmless to a
   * browser but needlessly changes the bytes a save would write back.
   */
  private static String restoreDoctype(String original, String formatted) {
    Matcher input = HtmlPrettifier.DOCTYPE_DECLARATION.matcher(original);
    if (!input.find()) {
      return formatted;
    }
    Matcher output = HtmlPrettifier.DOCTYPE_DECLARATION.matcher(formatted);
    if (!output.find() || input.group().equals(output.group())
        || !formatted.substring(0, output.start()).trim().isEmpty()) {
      return formatted;
    }
    return input.group() + formatted.substring(output.end());
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
      try {
        HtmlPrettifier.rewriteData(script, HtmlPrettifier.blockBody(this.jsPrettifier.prettify(script.data())));
      } catch (PrettificationException e) {
        // One pathological block must not cost the whole page its formatting; the tag keeps
        // whatever jsoup parsed out of it.
      }
    }
    for (Element style : document.select("style")) {
      if (HtmlPrettifier.isWhitespaceSensitive(style)) continue;
      try {
        HtmlPrettifier.rewriteData(style, HtmlPrettifier.blockBody(this.cssPrettifier.prettify(style.data())));
      } catch (PrettificationException e) {
        // As above: leave this block alone rather than failing the page.
      }
    }
  }

  /**
   * Deepest element nesting under {@code root}, walked iteratively so even adversarial markup
   * cannot overflow the stack getting here.
   */
  private static int elementDepth(Node root) {
    Deque<Node> nodes = new ArrayDeque<Node>();
    Deque<Integer> depths = new ArrayDeque<Integer>();
    nodes.push(root);
    depths.push(Integer.valueOf(0));
    int deepest = 0;
    while (!nodes.isEmpty()) {
      Node node = nodes.pop();
      int depth = depths.pop().intValue();
      if (depth > deepest) {
        deepest = depth;
        if (deepest > MAX_ELEMENT_DEPTH) {
          return deepest;
        }
      }
      for (Node child : node.childNodes()) {
        if (child instanceof Element) {
          nodes.push(child);
          depths.push(Integer.valueOf(depth + 1));
        }
      }
    }
    return deepest;
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
          // Padding is capped: embeddedAt is an attacker-controlled column and a crafted
          // long-indent line must not allocate megabytes of spaces per embedded line.
          int pad = Math.min(embeddedAt, PrettyPrettifier.MAX_INDENT_DEPTH * PrettyPrettifier.INDENT_WIDTH);
          Matcher close = HtmlPrettifier.CLOSING_TAG.matcher(trimmed);
          if (!close.find()) {
            // Keep the embedded code's own relative indentation, one level in from its tag.
            out.append(" ".repeat(pad + width)).append(line);
          }
          else if (close.start() == 0) {
            out.append(" ".repeat(pad)).append(trimmed);
          }
          else {
            int cut = line.length() - trimmed.length() + close.start();
            out.append(" ".repeat(pad + width)).append(line, 0, cut);
            out.append('\n').append(" ".repeat(pad)).append(line, cut, line.length());
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


package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Indentation-only HTML formatting. The input is tokenised into tags and text and laid out with
 * newlines and indentation, but no byte is ever added, removed, moved or rewritten: unclosed tags
 * stay unclosed, fragments stay fragments, entities and attribute quoting are untouched.
 */
public class HtmlPrettifier
implements PrettyPrettifier {
  /** Deepest nesting that is still laid out; deeper input goes back verbatim. */
  private static final int MAX_ELEMENT_DEPTH = 256;
  /** Elements that never take a closing tag, so they leave the indent level alone. */
  private static final Set<String> VOID_ELEMENTS = new HashSet<String>();
  /**
   * Elements whose content is captured verbatim up to the matching close tag instead of being
   * tokenised: markup-looking bytes inside them are content.
   */
  private static final Set<String> VERBATIM_BLOCKS = new HashSet<String>();

  static {
    String[] voids = {
      "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
      "source", "track", "wbr"
    };
    for (String tag : voids) {
      VOID_ELEMENTS.add(tag);
    }
    String[] verbatim = {"textarea", "pre", "template", "title"};
    for (String tag : verbatim) {
      VERBATIM_BLOCKS.add(tag);
    }
  }

  private final JavaScriptPrettifier jsPrettifier = new JavaScriptPrettifier();
  private final CssPrettifier cssPrettifier = new CssPrettifier();
  private final Map<String, MarkupIndenter.BlockFormatter> blockFormatters;

  {
    Map<String, MarkupIndenter.BlockFormatter> formatters =
        new HashMap<String, MarkupIndenter.BlockFormatter>();
    formatters.put("script", new MarkupIndenter.BlockFormatter() {
      @Override
      public String format(String content) throws Exception {
        return HtmlPrettifier.this.jsPrettifier.prettify(content);
      }
    });
    formatters.put("style", new MarkupIndenter.BlockFormatter() {
      @Override
      public String format(String content) throws Exception {
        return HtmlPrettifier.this.cssPrettifier.prettify(content);
      }
    });
    this.blockFormatters = Collections.unmodifiableMap(formatters);
  }

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.HTML;
  }

  @Override
  public String prettify(String body) throws PrettificationException {
    // In HTML parsing a self-closing slash never closes a raw-text element (<script/> still
    // opens one), so the indenter is told to ignore it there.
    return MarkupIndenter.format(
        body, VOID_ELEMENTS, VERBATIM_BLOCKS, this.blockFormatters, true, MAX_ELEMENT_DEPTH);
  }
}

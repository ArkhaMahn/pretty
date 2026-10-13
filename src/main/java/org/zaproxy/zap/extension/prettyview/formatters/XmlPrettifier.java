package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.Collections;
import java.util.HashSet;

/**
 * Indentation-only XML formatting. The input is tokenised into tags and text and laid out with
 * newlines and indentation, but no byte is ever added, removed, moved or rewritten: the {@code
 * <?xml?>} prolog, empty-element spellings and entity references all survive untouched, and
 * malformed input is still laid out structurally instead of failing. A DOM round-trip would
 * instead drop the prolog and normalise the markup - exactly the rewriting this view must not do
 * to responses under analysis.
 */
public class XmlPrettifier
implements PrettyPrettifier {
  /**
   * Deepest nesting that is still laid out. Data XML legitimately nests deeper than HTML, hence
   * the roomier budget; anything past it goes back verbatim.
   */
  private static final int MAX_ELEMENT_DEPTH = 1024;

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.XML;
  }

  @Override
  public String prettify(String body) throws PrettificationException {
    // XML has no void elements and no raw-text elements: every element closes explicitly, and
    // CDATA sections, comments and processing instructions are tokenised as special blocks.
    return MarkupIndenter.format(
        body,
        new HashSet<String>(),
        new HashSet<String>(),
        Collections.<String, MarkupIndenter.BlockFormatter>emptyMap(),
        false,
        MAX_ELEMENT_DEPTH);
  }
}

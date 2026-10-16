package org.zaproxy.zap.extension.prettyview.formatters;

import org.zaproxy.zap.extension.prettyview.detect.PayloadFormat;

public interface PrettyPrettifier {
  /**
   * One level of indentation in prettified output.
   *
   * <p>Two spaces, matching Burp's Pretty tab: their indenter draws {@code " ".repeat(2 * level)}.
   */
  String INDENT = "  ";

  /** Width of {@link #INDENT}, for the APIs that want a column count instead of a string. */
  int INDENT_WIDTH = INDENT.length();

  /**
   * Largest formatted output any prettifier may emit. Nesting multiplies indentation per line, so a
   * few hundred kilobytes of unmatched braces can otherwise unfold into gigabytes of padding; past
   * this the prettifier bails out and the manager renders the body verbatim. Eight megabytes stays
   * well inside what the editor holds smoothly.
   */
  int MAX_OUTPUT_CHARS = 8000000;

  /**
   * Deepest nesting level that is still drawn. Attacker input can open thousands of braces, and
   * emitting {@code depth × INDENT} per line would turn a 5 MB body into gigabytes of padding, so
   * the hand-written indenters draw deeper levels flat at this ceiling instead.
   */
  int MAX_INDENT_DEPTH = 64;

  /**
   * A body longer than this, holding a line longer than {@link #MINIFIED_LINE_CHARS}, is
   * minified: it stays verbatim instead of formatted, so a minified bundle counts as the lines
   * it has rather than the thousands formatting would unfold it into. Shorter bodies always
   * format.
   */
  int MINIFIED_TEXT_CHARS = 32768;
  /** Longest line that still formats inside a minified-sized body; past it the body is minified. */
  int MINIFIED_LINE_CHARS = 8000;

  /**
   * Whether {@code body} is minified: long overall with an over-long line in it. Minified
   * bundles are highlighted but never reformatted.
   */
  static boolean isMinified(String body) {
    if (body == null || body.length() < MINIFIED_TEXT_CHARS) {
      return false;
    }
    int start = 0;
    for (int i = 0; i < body.length(); ++i) {
      char c = body.charAt(i);
      if (c != '\n' && c != '\r') {
        continue;
      }
      if (i - start > MINIFIED_LINE_CHARS) {
        return true;
      }
      start = i + 1;
    }
    return body.length() - start > MINIFIED_LINE_CHARS;
  }

  public SupportedFormat getSupportedFormat();

  public String prettify(String var1) throws PrettificationException;

  public static enum SupportedFormat {
    JSON,
    GRAPHQL,
    HTML,
    XML,
    CSS,
    JAVASCRIPT,
    FORM_URLENCODED,
    MULTIPART,
    SQL,
    CSV,
    SSE,
    PLAIN_TEXT;

    public PayloadFormat toPayloadFormat() {
      return PayloadFormat.valueOf(this.name());
    }

    public static SupportedFormat of(PayloadFormat format) {
      if (format == null) {
        return null;
      }
      try {
        return SupportedFormat.valueOf(format.name());
      }
      catch (IllegalArgumentException e) {
        return null;
      }
    }
  }
}


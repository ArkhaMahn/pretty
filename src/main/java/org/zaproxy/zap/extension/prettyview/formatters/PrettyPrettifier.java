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


package org.zaproxy.zap.extension.prettyview.formatters;

import org.zaproxy.zap.extension.prettyview.detect.PayloadFormat;

public interface PrettyPrettifier {
  /**
   * One level of indentation in prettified output.
   *
   * <p>Four spaces, which is what both backends that do their own indenting default to: jsoup's
   * {@code indentAmount} and Xalan's {@code indent-amount} are 4 unless told otherwise, so this value
   * leaves them at their own default rather than overriding them.
   */
  String INDENT = "    ";

  /** Width of {@link #INDENT}, for the APIs that want a column count instead of a string. */
  int INDENT_WIDTH = INDENT.length();

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


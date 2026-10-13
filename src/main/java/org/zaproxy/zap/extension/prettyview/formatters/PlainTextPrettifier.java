package org.zaproxy.zap.extension.prettyview.formatters;

/**
 * Lines verbatim. Folding whitespace runs or stripping line-leading spaces would show bytes that
 * are not in the message, so the body goes back as it stands; long lines are wrapped by the
 * display layer instead.
 */
public class PlainTextPrettifier
implements PrettyPrettifier {
  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.PLAIN_TEXT;
  }

  @Override
  public String prettify(String body) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    return body;
  }
}

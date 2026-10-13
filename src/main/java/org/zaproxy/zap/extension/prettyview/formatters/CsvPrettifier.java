package org.zaproxy.zap.extension.prettyview.formatters;

/**
 * Lines verbatim. Parsing CSV to pad cells or strip quotes would rewrite the bytes on display
 * (padding invents spaces, quote-stripping deletes them), so the body goes back as it stands;
 * long lines are wrapped by the display layer instead.
 */
public class CsvPrettifier
implements PrettyPrettifier {
  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.CSV;
  }

  @Override
  public String prettify(String body) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    return body;
  }
}

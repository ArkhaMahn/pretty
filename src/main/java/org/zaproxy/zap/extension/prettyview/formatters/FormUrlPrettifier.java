package org.zaproxy.zap.extension.prettyview.formatters;

/**
 * One pair per line, bytes verbatim. The display never decodes percent-encoding and never adds
 * markers: decoding would show bytes that are not in the message.
 */
public class FormUrlPrettifier
implements PrettyPrettifier {
  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.FORM_URLENCODED;
  }

  @Override
  public String prettify(String body) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    // Split pairs apart for readability, one per line, keeping every '&' separator at its
    // line end; every segment - including empty ones - is emitted exactly as it stands.
    // Global size guards bound the work, so no pair cap or footer here.
    String[] pairs = body.split("&", -1);
    StringBuilder out = new StringBuilder(body.length() + pairs.length);
    for (int i = 0; i < pairs.length; ++i) {
      if (i > 0) {
        out.append('\n');
      }
      out.append(pairs[i]);
      if (i < pairs.length - 1) {
        out.append('&');
      }
    }
    return out.toString();
  }
}

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
    // line end; every segment - including empty ones - is emitted exactly as it stands, except
    // that line breaks are stripped from each pair. A raw newline cannot occur inside a genuine
    // pair (a newline in a value travels percent-encoded), so any break here is either display
    // residue from a previous pass or a half-typed edit - and keeping it would grow a blank
    // line on every pass, so the formatter could never settle. Stripping matches save(), which
    // rejoins pairs over newlines anyway, so the wire bytes are identical either way.
    // Global size guards bound the work, so no pair cap or footer here.
    String[] pairs = body.split("&", -1);
    StringBuilder out = new StringBuilder(body.length() + pairs.length);
    for (int i = 0; i < pairs.length; ++i) {
      if (i > 0) {
        out.append('\n');
      }
      String pair = pairs[i];
      for (int j = 0; j < pair.length(); ++j) {
        char c = pair.charAt(j);
        if (c != '\n' && c != '\r') {
          out.append(c);
        }
      }
      if (i < pairs.length - 1) {
        out.append('&');
      }
    }
    return out.toString();
  }
}

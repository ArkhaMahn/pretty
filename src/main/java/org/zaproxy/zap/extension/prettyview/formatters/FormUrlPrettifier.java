package org.zaproxy.zap.extension.prettyview.formatters;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class FormUrlPrettifier
implements PrettyPrettifier {
  private static final int MAX_PAIRS = 5000;

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.FORM_URLENCODED;
  }

  @Override
  public String prettify(String body) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(Math.min(body.length() * 2, 65536));
    // Stream pairs with indexOf instead of split(): a 5 MB run of "&" must not materialise a
    // millions-strong array when only the first MAX_PAIRS are drawn. The tail is still scanned
    // to count the omitted remainder, which allocates nothing.
    int total = 0;
    int rendered = 0;
    int start = 0;
    while (true) {
      int amp = body.indexOf(38, start);
      String pair = amp < 0 ? body.substring(start) : body.substring(start, amp);
      ++total;
      if (rendered < MAX_PAIRS) {
        ++rendered;
        if (!pair.isEmpty()) {
          int eq = pair.indexOf(61);
          String rawName = eq < 0 ? pair : pair.substring(0, eq);
          String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
          out.append(FormUrlPrettifier.decode(rawName));
          if (eq < 0) {
            out.append(" (no value)");
          } else {
            out.append(" = ");
            out.append(FormUrlPrettifier.decode(rawValue));
          }
          if (FormUrlPrettifier.isEncoded(rawName) || FormUrlPrettifier.isEncoded(rawValue)) {
            out.append("\n    raw: ").append(pair);
          }
          out.append('\n');
        }
      }
      if (amp < 0) {
        break;
      }
      start = amp + 1;
    }
    if (total > rendered) {
      out.append("... ").append(total - rendered).append(" further parameter(s) omitted\n");
    }
    return out.toString();
  }

  private static boolean isEncoded(String token) {
    return token != null && (token.indexOf(37) >= 0 || token.indexOf(43) >= 0);
  }

  private static String decode(String token) {
    if (token == null || token.isEmpty()) {
      return "";
    }
    try {
      return URLDecoder.decode(token, StandardCharsets.UTF_8.name());
    }
    catch (UnsupportedEncodingException | IllegalArgumentException e) {
      return token;
    }
  }
}


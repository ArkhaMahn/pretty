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
    String[] pairs = body.split("&", -1);
    int limit = Math.min(pairs.length, 5000);
    for (int i = 0; i < limit; ++i) {
      String pair = pairs[i];
      if (pair.isEmpty()) continue;
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
    if (pairs.length > limit) {
      out.append("... ").append(pairs.length - limit).append(" further parameter(s) omitted\n");
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


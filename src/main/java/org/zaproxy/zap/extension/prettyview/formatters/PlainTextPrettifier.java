package org.zaproxy.zap.extension.prettyview.formatters;

public class PlainTextPrettifier
implements PrettyPrettifier {
  private static final int MAX_INNER_RUN = 4;

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.PLAIN_TEXT;
  }

  @Override
  public String prettify(String body) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    String normalised = body.replace("\r\n", "\n").replace('\r', '\n');
    StringBuilder out = new StringBuilder(normalised.length() + 16);
    int length = normalised.length();
    boolean atLineStart = true;
    for (int i = 0; i < length; ++i) {
      char c = normalised.charAt(i);
      if (c == '\n') {
        if (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
          out.setLength(out.length() - 1);
        }
        out.append('\n');
        atLineStart = true;
        continue;
      }
      if (Character.isWhitespace(c)) {
        if (atLineStart) continue;
        int run = 0;
        while (i + run < length && Character.isWhitespace(normalised.charAt(i + run)) && normalised.charAt(i + run) != '\n') {
          ++run;
        }
        out.append(" ".repeat(Math.min(run, 4)));
        i += run - 1;
        continue;
      }
      out.append(c);
      atLineStart = false;
    }
    return out.toString();
  }
}


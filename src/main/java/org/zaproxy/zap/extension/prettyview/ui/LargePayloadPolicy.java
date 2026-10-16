package org.zaproxy.zap.extension.prettyview.ui;

public final class LargePayloadPolicy {
  /**
   * Past this many lines, wrapping switches off on its own. An untouched toggle is overruled
   * for scroll performance - unless the View toggle was explicitly used, which always wins.
   */
  public static final int MAX_WRAP_LINES = 20000;

  private LargePayloadPolicy() {
  }

  /**
   * Whether the formatted text is wrapped: the toggle says yes by default, but past {@link
   * #MAX_WRAP_LINES} lines an untouched toggle is overruled for scroll performance. See {@link
   * PrettyLineWrap}.
   */
  public static boolean shouldWrap(String formattedText) {
    if (!PrettyLineWrap.isEnabled()) {
      return false;
    }
    return PrettyLineWrap.isTouched() || LargePayloadPolicy.countLines(formattedText) <= MAX_WRAP_LINES;
  }

  /**
   * Whether the formatted text was unwrapped by the line limit rather than by choice, for the
   * notice that says so.
   */
  public static boolean wasAutoUnwrapped(String formattedText) {
    return PrettyLineWrap.isEnabled()
        && !PrettyLineWrap.isTouched()
        && LargePayloadPolicy.countLines(formattedText) > MAX_WRAP_LINES;
  }

  static int countLines(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    int lines = 1;
    for (int i = 0; i < text.length(); ++i) {
      if (text.charAt(i) == '\n') {
        ++lines;
      }
    }
    return lines;
  }

}

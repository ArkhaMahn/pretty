package org.zaproxy.zap.extension.prettyview.ui;

/**
 * Whether Pretty wraps lines. On by default: lines scroll and fold exactly like Burp's Pretty,
 * whose wrap toggle is the View menu's line-wrapping entry. Turning wrapping off restores code
 * folding, which cannot share a line with wrapping - Burp drops folding the moment wrapping
 * goes on, for the same reason. Session state: every Pretty view follows it, and the toggle in
 * the View menu flips it live.
 *
 * <p>Burp never wraps its Pretty tab at all, which is what keeps its scrolling smooth no matter
 * the size: wrapped scrolling walks every line on every move, so past {@link
 * LargePayloadPolicy#MAX_WRAP_LINES} lines this view unwraps on its own (with a notice) unless
 * the toggle was explicitly used, in which case the explicit choice wins and owns the cost.
 */
final class PrettyLineWrap {
  private static volatile boolean enabled = true;
  private static volatile boolean touched;

  private PrettyLineWrap() {
  }

  static boolean isEnabled() {
    return PrettyLineWrap.enabled;
  }

  static void setEnabled(boolean enabled) {
    PrettyLineWrap.enabled = enabled;
    PrettyLineWrap.touched = true;
  }

  static boolean isTouched() {
    return PrettyLineWrap.touched;
  }
}

package org.zaproxy.zap.extension.prettyview.ui;

/**
 * Whether Pretty wraps lines. On by default: lines scroll and fold exactly like Burp's Pretty,
 * whose wrap toggle is the View menu's line-wrapping entry. Turning wrapping off restores code
 * folding, which cannot share a line with wrapping - Burp drops folding the moment wrapping
 * goes on, for the same reason. Session state: every Pretty view follows it, and the toggle in
 * the View menu flips it live.
 */
final class PrettyLineWrap {
  private static volatile boolean enabled = true;

  private PrettyLineWrap() {
  }

  static boolean isEnabled() {
    return PrettyLineWrap.enabled;
  }

  static void setEnabled(boolean enabled) {
    PrettyLineWrap.enabled = enabled;
  }
}

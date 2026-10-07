package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.Locale;

public final class PrettifyThreshold {
  public static final int DEFAULT_THRESHOLD_CHARS = 0x500000;
  public static final int HARD_LIMIT_CHARS = 0x2000000;
  private final int thresholdChars;

  public PrettifyThreshold(int thresholdChars) {
    if (thresholdChars <= 0) {
      throw new IllegalArgumentException("thresholdChars must be positive");
    }
    if (thresholdChars > 0x2000000) {
      throw new IllegalArgumentException("thresholdChars must not exceed 33554432");
    }
    this.thresholdChars = thresholdChars;
  }

  public static PrettifyThreshold defaultThreshold() {
    return new PrettifyThreshold(0x500000);
  }

  public boolean allowsPrettifying(int length) {
    return length <= this.thresholdChars && length <= 0x2000000;
  }

  public String exceededNote(int length) {
    return "payload of " + PrettifyThreshold.toKilobytes(length) + " KB exceeds the " + PrettifyThreshold.toKilobytes(this.thresholdChars) + " KB prettify threshold; showing the fast raw view";
  }

  private static String toKilobytes(int length) {
    return String.format(Locale.ROOT, "%.1f", (double)length / 1024.0);
  }

  public int getThresholdChars() {
    return this.thresholdChars;
  }
}


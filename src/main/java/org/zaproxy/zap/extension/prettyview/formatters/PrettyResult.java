package org.zaproxy.zap.extension.prettyview.formatters;

import org.zaproxy.zap.extension.prettyview.detect.PayloadFormat;

public final class PrettyResult {
  private final String text;
  private final PayloadFormat format;
  private final boolean fallback;
  private final String note;
  private final boolean overThreshold;

  private PrettyResult(String text, PayloadFormat format, boolean fallback, String note, boolean overThreshold) {
    this.text = text;
    this.format = format;
    this.fallback = fallback;
    this.note = note;
    this.overThreshold = overThreshold;
  }

  public static PrettyResult formatted(String text, PayloadFormat format) {
    return new PrettyResult(text, format, false, null, false);
  }

  public static PrettyResult verbatim(String text, PayloadFormat format, String note) {
    return new PrettyResult(text, format, true, note, false);
  }

  public static PrettyResult rawOverThreshold(String text, PayloadFormat format, String note) {
    return new PrettyResult(text, format, false, note, true);
  }

  public String getText() {
    return this.text;
  }

  public PayloadFormat getFormat() {
    return this.format;
  }

  public boolean isFallback() {
    return this.fallback;
  }

  public String getNote() {
    return this.note;
  }

  public boolean isOverThreshold() {
    return this.overThreshold;
  }

  public boolean hasNote() {
    return this.note != null && !this.note.isEmpty();
  }
}


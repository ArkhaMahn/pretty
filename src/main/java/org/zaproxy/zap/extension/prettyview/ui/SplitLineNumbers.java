package org.zaproxy.zap.extension.prettyview.ui;

import javax.swing.text.Element;
import org.fife.ui.rtextarea.LineNumberFormatter;

/**
 * Gutter numbers that count logical lines, not split pieces.
 *
 * <p>Over-long lines are broken before they reach the editor so they stay paintable, but a
 * break is not a new line: continuation pieces show no number, exactly like the editor's own
 * soft-wrapped rows. Copying, searching and saving still map through the split by offset and
 * never see this.
 */
final class SplitLineNumbers
implements LineNumberFormatter {
  /** Logical number per document line, or -1 for a continuation piece. */
  private final int[] logical;
  /** Width the gutter reserves, in digits. */
  private final int maxLength;
  /** First displayed number, so the document line index recovers from the painted one. */
  private final int startIndex;

  private SplitLineNumbers(int[] logical, int maxLength, int startIndex) {
    this.logical = logical;
    this.maxLength = maxLength;
    this.startIndex = startIndex;
  }

  /**
   * Numbers every document line of {@code root}: continuation pieces blank, everything else the
   * count of formatted newlines before it plus {@code startIndex}. One sweep: both the line
   * starts and the mapped offsets run forward only.
   */
  static SplitLineNumbers forSplit(
      DisplayLineSplitter.Split split, Element root, int startIndex) {
    int lines = root.getElementCount();
    int[] logical = new int[lines];
    String original = split.originalText();
    int newlines = 0;
    int scanned = 0;
    int widest = startIndex;
    for (int i = 0; i < lines; ++i) {
      int start = root.getElement(i).getStartOffset();
      if (split.isContinuationStart(start)) {
        logical[i] = -1;
        continue;
      }
      int mapped = Math.min(split.toOriginalOffset(start), original.length());
      while (scanned < mapped) {
        if (original.charAt(scanned) == '\n') {
          ++newlines;
        }
        ++scanned;
      }
      logical[i] = startIndex + newlines;
      widest = Math.max(widest, logical[i]);
    }
    return new SplitLineNumbers(logical, String.valueOf(widest).length(), startIndex);
  }

  @Override
  public String format(int lineNumber) {
    int index = lineNumber - this.startIndex;
    if (index < 0 || index >= this.logical.length || this.logical[index] < 0) {
      return "";
    }
    return String.valueOf(this.logical[index]);
  }

  @Override
  public int getMaxLength(int maxLineNumber) {
    return this.maxLength;
  }
}

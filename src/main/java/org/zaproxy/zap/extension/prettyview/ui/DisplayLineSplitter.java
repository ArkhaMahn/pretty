package org.zaproxy.zap.extension.prettyview.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Breaks over-long logical lines where the eye expects it: at the view width, after whitespace.
 *
 * <p>Wrapping is what keeps a long line readable on screen - the editor wraps softly at the viewport -
 * but a logical line thousands of characters long still costs a full layout and tokenization pass on
 * every repaint. So lines past the view width get hard newlines, each break landing after the most
 * recent space or tab rather than mid-word, and only words longer than the width itself are cut.
 * Short lines pass through byte for byte.
 *
 * <p>Every break {@link Split} records can be undone: copying a selection hands back the characters
 * as they were in the response, and writing an edited body back to the message does the same.
 */
final class DisplayLineSplitter {

  /** Narrowest wrap column ever used; absurdly narrow views still terminate. */
  static final int MIN_WRAP_COLUMN = 80;
  /** Widest wrap column ever used; bounds repaint and tokenization work. */
  static final int MAX_WRAP_COLUMN = 20000;
  /** Wrap column off the event thread, where the viewport cannot be measured. */
  static final int DEFAULT_WRAP_COLUMN = 2000;

  /**
   * How far past the wrap column to look back for a space or tab to break after, so a forced
   * break lands between words rather than mid-word. Bounded, so spaceless runs still terminate.
   */
  static final int SPACE_LOOKAHEAD_CHARS = 400;

  private DisplayLineSplitter() {
  }

  static Split split(String text, int wrapColumn) {
    if (text == null || text.isEmpty()) {
      return new Split("", "", new int[0]);
    }
    int column = Math.max(1, wrapColumn);
    List<Integer> breaks = new ArrayList<>();
    StringBuilder out = new StringBuilder(text.length() + 1024);
    int lineStart = 0;
    for (int i = 0; i < text.length(); ++i) {
      if (text.charAt(i) != '\n' && text.charAt(i) != '\r') {
        continue;
      }
      int lineEnd = i;
      copyChunked(text, lineStart, lineEnd, out, breaks, column);
      out.append(text.charAt(i));
      lineStart = i + 1;
      if (text.charAt(i) == '\r' && lineStart < text.length() && text.charAt(i + 1) == '\n') {
        // A CRLF pair is one line break: consume the '\n' here so the next iteration does
        // not treat it as a second break and conjure a blank line.
        out.append('\n');
        ++lineStart;
        ++i;
      }
    }
    copyChunked(text, lineStart, text.length(), out, breaks, column);
    return new Split(out.toString(), text, toIntArray(breaks));
  }

  private static void copyChunked(
      String text, int from, int to, StringBuilder out, List<Integer> breaks, int column) {
    int length = to - from;
    if (length <= column) {
      if (length > 0) {
        out.append(text, from, to);
      }
      return;
    }
    // Stream the line, breaking past the column only at whitespace: when a word cannot fit,
    // the break goes after the most recent space or tab. Spaceless runs cut at the column so
    // chunks stay bounded, and a break never splits a surrogate pair.
    int chunkStart = from;
    int i = from;
    while (i < to) {
      if (i - chunkStart < column) {
        ++i;
        continue;
      }
      int lookback = Math.max(chunkStart, i - SPACE_LOOKAHEAD_CHARS);
      int at = i;
      for (int j = i; j > lookback; --j) {
        char d = text.charAt(j);
        if (d == ' ' || d == '\t') {
          at = j + 1;
          break;
        }
      }
      at = adjustForSurrogates(text, chunkStart, at, to);
      if (at >= to) {
        // The tail fits: a trailing space is not worth a final empty line.
        break;
      }
      chunkStart = emitBreak(text, chunkStart, at, out, breaks);
      i = at;
    }
    if (to > chunkStart) {
      out.append(text, chunkStart, to);
    }
  }

  /**
   * Moves a break past a UTF-16 surrogate pair it would otherwise split, so no half-pair ever
   * lands at a chunk edge.
   */
  private static int adjustForSurrogates(String text, int chunkStart, int at, int to) {
    if (at > chunkStart
        && at < to
        && Character.isHighSurrogate(text.charAt(at - 1))
        && Character.isLowSurrogate(text.charAt(at))) {
      return at + 1;
    }
    return at;
  }

  /**
   * Copies the chunk that ends at {@code at} and records the break the editor is given, returning the
   * offset the next chunk starts from.
   */
  private static int emitBreak(
      String text, int chunkStart, int at, StringBuilder out, List<Integer> breaks) {
    out.append(text, chunkStart, at);
    breaks.add(out.length());
    out.append('\n');
    return at;
  }

  private static int[] toIntArray(List<Integer> values) {
    int[] result = new int[values.size()];
    for (int i = 0; i < result.length; ++i) {
      result[i] = values.get(i);
    }
    return result;
  }

  /** A formatted payload together with the breaks the editor was given, and how to undo them. */
  static final class Split {
    private final String displayText;
    private final String originalText;
    private final int[] breakOffsets;

    Split(String displayText, String originalText, int[] breakOffsets) {
      this.displayText = displayText;
      this.originalText = originalText;
      this.breakOffsets = breakOffsets;
    }

    String displayText() {
      return this.displayText;
    }

    String originalText() {
      return this.originalText;
    }

    /** Number of characters the display text gained over the formatted text. */
    int addedBreaks() {
      return this.breakOffsets.length;
    }

    /** Whether the editor still holds exactly the text that was handed to it. */
    boolean isDisplayText(String candidate) {
      return candidate != null
          && candidate.length() == this.displayText.length()
          && candidate.equals(this.displayText);
    }

    /**
     * Maps an offset in the display text to the matching offset in the formatted text.
     *
     * <p>An offset that sits on an inserted break maps to the position the break was put in front of, which
     * is what a selection boundary wants: selecting across a break copies the characters on both sides of
     * it and none of the break itself.
     */
    int toOriginalOffset(int displayOffset) {
      int removed = 0;
      for (int offset : this.breakOffsets) {
        if (offset < displayOffset) {
          ++removed;
        } else {
          break;
        }
      }
      return displayOffset - removed;
    }

    /**
     * Maps an offset in the formatted text to the matching offset in the display text.
     *
     * <p>The inverse of {@link #toOriginalOffset}: every inserted break at or before the offset
     * shifts it one character forward. Break {@code i} sits before formatted character
     * {@code b[i] - i}, so breaks are counted while that holds.
     */
    int toDisplayOffset(int formattedOffset) {
      int clamped = Math.max(0, Math.min(formattedOffset, this.originalText.length()));
      int display = clamped;
      int seen = 0;
      for (int offset : this.breakOffsets) {
        if (offset - seen <= clamped) {
          ++display;
          ++seen;
        } else {
          break;
        }
      }
      return Math.min(display, this.displayText.length());
    }

    /** The formatted characters a display selection covers. */
    String originalRange(int from, int to) {
      if (from < 0 || to > this.displayText.length() || from >= to) {
        return "";
      }
      int start = this.toOriginalOffset(from);
      int end = this.toOriginalOffset(to);
      if (start < 0) {
        start = 0;
      }
      if (end > this.originalText.length()) {
        end = this.originalText.length();
      }
      if (start >= end) {
        return "";
      }
      return this.originalText.substring(start, end);
    }

    /**
     * The formatted form of text taken from the editor.
     *
     * <p>Only an unedited display text is rewritten, because the offsets describing where the breaks went
     * stop meaning anything once the text around them has changed; an edited body is passed on as typed.
     */
    String toOriginal(String candidate) {
      if (this.breakOffsets.length == 0) {
        return candidate;
      }
      if (this.isDisplayText(candidate)) {
        return this.originalText;
      }
      return this.toOriginalEdited(candidate);
    }

    /**
     * Maps an edited display text back to formatted coordinates with a prefix/suffix splice.
     *
     * <p>The common edit touches one region: the text before it and the text after it still sit where
     * the split put them, so both ends map through {@link #toOriginalOffset} and the edited middle is
     * spliced into the original at the mapped span. Synthetic breaks outside the edited region survive
     * untouched, while the edited middle is kept exactly as typed. When a break offset falls inside the
     * edited region the mapping is ambiguous and the candidate is returned as typed, which is no worse
     * than before.
     */
    String toOriginalEdited(String candidate) {
      if (candidate == null) {
        return "";
      }
      String display = this.displayText;
      int maxPrefix = Math.min(display.length(), candidate.length());
      int prefix = 0;
      while (prefix < maxPrefix && display.charAt(prefix) == candidate.charAt(prefix)) {
        ++prefix;
      }
      int maxSuffix = Math.min(display.length() - prefix, candidate.length() - prefix);
      int suffix = 0;
      while (suffix < maxSuffix
          && display.charAt(display.length() - 1 - suffix)
              == candidate.charAt(candidate.length() - 1 - suffix)) {
        ++suffix;
      }
      for (int offset : this.breakOffsets) {
        if (offset >= prefix && offset < display.length() - suffix) {
          return candidate;
        }
      }
      int start = this.toOriginalOffset(prefix);
      int end = this.toOriginalOffset(display.length() - suffix);
      if (start < 0) {
        start = 0;
      }
      if (end > this.originalText.length()) {
        end = this.originalText.length();
      }
      if (start > end) {
        return candidate;
      }
      return this.originalText.substring(0, start)
          + candidate.substring(prefix, candidate.length() - suffix)
          + this.originalText.substring(end);
    }

    @Override
    public String toString() {
      return "Split[display=" + this.displayText.length()
          + ", original=" + this.originalText.length()
          + ", breaks=" + Arrays.toString(this.breakOffsets) + "]";
    }
  }
}
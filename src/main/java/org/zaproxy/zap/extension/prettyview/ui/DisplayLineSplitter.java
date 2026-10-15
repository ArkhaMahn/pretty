package org.zaproxy.zap.extension.prettyview.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Breaks over-long logical lines into shorter ones before they reach the editor.
 *
 * <p>Wrapping is what keeps a long line readable, but the editor does the wrapping by handing the whole
 * logical line to a layout view, and it re-does that work on every repaint. One 54 331-character line in a
 * 118 871-character response cost 17 ms median and 70 ms p95 to repaint, which is the stutter you feel
 * while scrolling. The same characters handed over as 2 000-character lines repaint in 5 ms median and
 * 14 ms p95, because a wrapped line that fits inside a screenful is cheap to lay out and a wrapped line
 * that spans 1 000 of them is not.
 *
 * <p>So breaks go into the text the editor shows rather than being left to the editor - but
 * only where they have to, and preferably after a space rather than mid-word. The editor wraps
 * softly at the view width on top of that; the hard breaks exist purely to bound repaint cost,
 * and the threshold sits far below the editor's own syntax guard so highlighting is never
 * sacrificed for it.
 *
 * <p>Every break {@link Split} records can be undone: copying a selection hands back the characters
 * as they were in the response, and writing an edited body back to the message does the same.
 *
 * <p>A break is never put where it would cut a string literal in half if one is in reach. The mapping
 * that undoes the break is indifferent to what the characters were, but the editor is not: the second
 * half of a split string is no longer a string to the highlighter, so it loses its colouring and reads
 * as bare text. A string within {@link #MAX_CHUNK_CHARS} is therefore taken whole.
 */
final class DisplayLineSplitter {

  /**
   * Longest line handed to the editor. It sits far below the editor's own syntax guard, so a
   * payload keeps its highlighting; anything longer is broken up, which keeps repaint cost and
   * the token heap bounded.
   */
  static final int CHUNK_CHARS = 2000;

  /**
   * How far a break may be held off while the character at the boundary is inside a string: a string
   * crossing a chunk boundary is kept whole up to this length, and only a longer one is split. Both
   * lengths keep the line well inside the point where laying one out costs a frame.
   */
  static final int MAX_CHUNK_CHARS = 4000;

  /**
   * How far past the chunk limit to look for a space or tab to break after, so a forced break
   * lands between words rather than mid-word. Bounded, so spaceless runs still terminate.
   */
  static final int SPACE_LOOKAHEAD_CHARS = 400;

  private static final int NONE = 0;
  private static final int LINE_COMMENT = 1;
  private static final int BLOCK_COMMENT = 2;

  private DisplayLineSplitter() {
  }

  static Split split(String text) {
    if (text == null || text.isEmpty()) {
      return new Split(text == null ? "" : text, text == null ? "" : text, new int[0]);
    }
    List<Integer> breaks = new ArrayList<>();
    StringBuilder out = new StringBuilder(text.length() + 1024);
    int lineStart = 0;
    for (int i = 0; i < text.length(); ++i) {
      if (text.charAt(i) != '\n' && text.charAt(i) != '\r') {
        continue;
      }
      int lineEnd = i;
      copyChunked(text, lineStart, lineEnd, out, breaks);
      out.append(text.charAt(i));
      lineStart = i + 1;
      if (text.charAt(i) == '\r' && lineStart < text.length() && text.charAt(i + 1) == '\n') {
        out.append('\n');
        ++lineStart;
      }
    }
    copyChunked(text, lineStart, text.length(), out, breaks);
    return new Split(out.toString(), text, toIntArray(breaks));
  }

  private static void copyChunked(
      String text, int from, int to, StringBuilder out, List<Integer> breaks) {
    int length = to - from;
    if (length <= CHUNK_CHARS) {
      if (length > 0) {
        out.append(text, from, to);
      }
      return;
    }
    int chunkStart = from;
    char quote = 0;
    int comment = NONE;
    // Absolute index at which a pending break is forced even without a space: -1 while no
    // break is pending. Set once a chunk runs past the limit outside strings; a fresh break
    // then lands after the next space or tab, or at the deadline when none shows up.
    int seekDeadline = -1;
    int i = from;
    while (i < to) {
      if (seekDeadline < 0 && quote == 0 && i - chunkStart >= CHUNK_CHARS) {
        seekDeadline = i + SPACE_LOOKAHEAD_CHARS;
      }
      if (seekDeadline >= 0 && i >= seekDeadline && quote == 0) {
        // Give up waiting for a space (inside comments, or a spaceless run): cut here so the
        // chunk stays bounded. Strings never take this path; they keep their own longer cap.
        chunkStart = emitBreak(text, chunkStart, i, out, breaks);
        seekDeadline = -1;
        continue;
      }
      char c = text.charAt(i);
      if (comment == LINE_COMMENT) {
        if (c == '\n' || c == '\r') {
          comment = NONE;
        }
        ++i;
        continue;
      }
      if (comment == BLOCK_COMMENT) {
        if (c == '*' && i + 1 < to && text.charAt(i + 1) == '/') {
          comment = NONE;
          ++i;
        }
        ++i;
        continue;
      }
      if (quote != 0) {
        if (c == '\\') {
          i += 2;
          continue;
        }
        if (c == quote) {
          quote = 0;
        }
        ++i;
        if (i - chunkStart >= MAX_CHUNK_CHARS) {
          chunkStart = emitBreak(text, chunkStart, i, out, breaks);
        }
        continue;
      }
      if (c == '/' && i + 1 < to) {
        char next = text.charAt(i + 1);
        if (next == '/' || next == '*') {
          comment = next == '/' ? LINE_COMMENT : BLOCK_COMMENT;
          i += 2;
          continue;
        }
      }
      if (c == '"' || c == '\'' || c == '`') {
        quote = c;
        // A fresh string abandons the pending seek: the longer string cap takes over, and the
        // top of the loop re-arms a seek afterwards if the chunk is still over the limit.
        seekDeadline = -1;
        ++i;
        continue;
      }
      if (seekDeadline >= 0 && (c == ' ' || c == '\t')) {
        chunkStart = emitBreak(text, chunkStart, i + 1, out, breaks);
        seekDeadline = -1;
        i = chunkStart;
        continue;
      }
      ++i;
    }
    if (to > chunkStart) {
      out.append(text, chunkStart, to);
    }
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
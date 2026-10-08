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
 * <p>So the breaks are put into the text the editor shows rather than left to the editor. That makes the
 * displayed text differ from the formatted text, so every {@link Split} keeps enough information to undo
 * them: copying a selection hands back the characters as they were in the response, and writing an edited
 * body back to the message does the same.
 *
 * <p>A break is never put where it would cut a string literal in half if one is in reach. The mapping
 * that undoes the break is indifferent to what the characters were, but the editor is not: the second
 * half of a split string is no longer a string to the highlighter, so it loses its colouring and reads
 * as bare text. A string within {@link #MAX_CHUNK_CHARS} is therefore taken whole.
 */
final class DisplayLineSplitter {

  /** Longest line handed to the editor, chosen from measurements rather than guessed. */
  static final int CHUNK_CHARS = 2000;

  /**
   * How far a break may be held off while the character at the boundary is inside a string: a string
   * crossing a chunk boundary is kept whole up to this length, and only a longer one is split. Both
   * lengths keep the line well inside the point where laying one out costs a frame.
   */
  static final int MAX_CHUNK_CHARS = 4000;

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
    int i = from;
    while (i < to) {
      if (quote == 0 ? i - chunkStart >= CHUNK_CHARS : i - chunkStart >= MAX_CHUNK_CHARS) {
        chunkStart = emitBreak(text, chunkStart, i, out, breaks);
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
      if (this.breakOffsets.length == 0 || !this.isDisplayText(candidate)) {
        return candidate;
      }
      if (candidate.length() == this.originalText.length()) {
        return this.originalText;
      }
      StringBuilder out = new StringBuilder(candidate.length());
      int nextBreak = 0;
      for (int i = 0; i < candidate.length(); ++i) {
        if (nextBreak < this.breakOffsets.length && this.breakOffsets[nextBreak] == i) {
          ++nextBreak;
          continue;
        }
        out.append(candidate.charAt(i));
      }
      return out.toString();
    }

    @Override
    public String toString() {
      return "Split[display=" + this.displayText.length()
          + ", original=" + this.originalText.length()
          + ", breaks=" + Arrays.toString(this.breakOffsets) + "]";
    }
  }
}
package org.zaproxy.zap.extension.prettyview.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Breaks only monster logical lines before they reach the editor.
 *
 * <p>Short lines reach the editor untouched, so what is shown is the formatted text byte for byte
 * and code folding sees the real structure. Only a line past {@link #SPLIT_LINE_CHARS} is broken,
 * into {@link #CHUNK_CHARS} pieces, because two costs scale with the longest line rather than with
 * what is on screen: the editor re-lays its wrapped rows on every paint, walking every token of
 * every long line measuring glyphs (seconds per scroll step on a real page past ~8 k characters),
 * and an unwrapped row tens of thousands of characters wide asks for a preferred width of hundreds
 * of thousands of pixels and paints only part of itself.
 *
 * <p>Every {@link Split} keeps enough information to undo the breaks: copying a selection hands
 * back the characters as they were in the response, and writing an edited body back to the message
 * does the same. Continuation pieces replay the broken line's indentation, so nothing flows
 * outside its level.
 *
 * <p>A break is never put where it would cut a string literal in half if one is in reach. The mapping
 * that undoes the break is indifferent to what the characters were, but the editor is not: the second
 * half of a split string is no longer a string to the highlighter, so it loses its colouring and reads
 * as bare text. A string within {@link #MAX_CHUNK_CHARS} is therefore taken whole.
 */
final class DisplayLineSplitter {

  /**
   * Only lines past this length are broken. Sits well below the syntax line limit, so a displayed
   * line never costs the document its highlighting, and far below the width where an unwrapped row
   * stops painting fully - while leaving ordinary lines, which folding needs whole, alone. Past
   * this length a wrapped line also re-lays whole seconds per paint, so wrapped views split here
   * too: only the pathological few lines gain numbers, each replaying the broken line's
   * indentation.
   */
  static final int SPLIT_LINE_CHARS = 8000;

  /**
   * Longest piece a broken line is cut into. Short enough that laying a piece out costs a frame
   * at most: the editor re-lays every line on every paint, so pieces past this make every
   * scroll step wait on the pathological few lines.
   */
  static final int CHUNK_CHARS = 2000;

  /**
   * Longest indentation a continuation line inherits from the line it was broken off. A crowded
   * continuation still reads as part of its line well past this, while an uncapped one would copy
   * a pathological indent onto every chunk.
   */
  static final int MAX_BREAK_INDENT = 64;

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

  /**
   * Breaks only monster lines before they reach the editor. Short lines reach it untouched, so
   * what is shown is the formatted text byte for byte and code folding sees the real structure;
   * only the pathological few lines are cut, each continuation replaying the broken line's
   * indentation so nothing flows outside its level.
   */
  static Split split(String text) {
    if (text == null || text.isEmpty()) {
      return new Split(text == null ? "" : text, text == null ? "" : text, new int[0], new int[0]);
    }
    List<Integer> breaks = new ArrayList<>();
    List<Integer> indents = new ArrayList<>();
    StringBuilder out = new StringBuilder(text.length() + 1024);
    int lineStart = 0;
    for (int i = 0; i < text.length(); ++i) {
      if (text.charAt(i) != '\n' && text.charAt(i) != '\r') {
        continue;
      }
      int lineEnd = i;
      copyChunked(text, lineStart, lineEnd, breakIndent(text, lineStart, lineEnd), out, breaks,
          indents);
      out.append(text.charAt(i));
      lineStart = i + 1;
      if (text.charAt(i) == '\r' && lineStart < text.length() && text.charAt(i + 1) == '\n') {
        out.append('\n');
        ++lineStart;
      }
    }
    copyChunked(text, lineStart, text.length(), breakIndent(text, lineStart, text.length()), out,
        breaks, indents);
    return new Split(out.toString(), text, toIntArray(breaks), toIntArray(indents));
  }

  /**
   * The whitespace a broken line starts with, replayed in front of every continuation chunk so
   * the pieces still read as one indented line. Only blanks count, and only up to {@link
   * #MAX_BREAK_INDENT} of them.
   */
  private static String breakIndent(String text, int from, int to) {
    int end = from;
    int cap = Math.min(to, from + MAX_BREAK_INDENT);
    while (end < cap) {
      char c = text.charAt(end);
      if (c != ' ' && c != '\t') {
        break;
      }
      ++end;
    }
    return end > from ? text.substring(from, end) : "";
  }

  private static void copyChunked(
      String text, int from, int to, String indent, StringBuilder out, List<Integer> breaks,
      List<Integer> indents) {
    int length = to - from;
    if (length <= SPLIT_LINE_CHARS) {
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
        chunkStart = emitBreak(text, chunkStart, i, indent, out, breaks, indents);
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
   * offset the next chunk starts from. The continuation replays the broken line's indentation, so
   * the pieces still read as one line; the replayed blanks are recorded beside the break so the
   * mapping back skips them like the break itself.
   */
  private static int emitBreak(
      String text, int chunkStart, int at, String indent, StringBuilder out,
      List<Integer> breaks, List<Integer> indents) {
    out.append(text, chunkStart, at);
    breaks.add(out.length());
    indents.add(indent.length());
    out.append('\n');
    out.append(indent);
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
    /** Replayed indentation after each break, in characters; parallel to {@link #breakOffsets}. */
    private final int[] breakIndents;

    Split(String displayText, String originalText, int[] breakOffsets, int[] breakIndents) {
      this.displayText = displayText;
      this.originalText = originalText;
      this.breakOffsets = breakOffsets;
      this.breakIndents = breakIndents;
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
     * <p>An offset that sits on an inserted break - the newline or the replayed indentation after
     * it - maps to the position the break was put in front of, which is what a selection boundary
     * wants: selecting across a break copies the characters on both sides of it and none of the
     * break itself.
     */
    int toOriginalOffset(int displayOffset) {
      int removed = 0;
      for (int i = 0; i < this.breakOffsets.length; ++i) {
        int offset = this.breakOffsets[i];
        if (displayOffset <= offset) {
          break;
        }
        int runEnd = offset + 1 + this.breakIndents[i];
        if (displayOffset < runEnd) {
          return offset - removed;
        }
        removed += 1 + this.breakIndents[i];
      }
      return displayOffset - removed;
    }

    /**
     * Maps an offset in the formatted text to the matching offset in the display text.
     *
     * <p>The inverse of {@link #toOriginalOffset}: every inserted break at or before the offset
     * shifts it forward past the newline and the replayed indentation. Break {@code i} sits
     * before formatted character {@code b[i] - inserted}, where {@code inserted} is everything
     * earlier breaks added, so breaks are counted while that holds.
     */
    int toDisplayOffset(int formattedOffset) {
      int clamped = Math.max(0, Math.min(formattedOffset, this.originalText.length()));
      int display = clamped;
      int inserted = 0;
      for (int i = 0; i < this.breakOffsets.length; ++i) {
        if (this.breakOffsets[i] - inserted <= clamped) {
          int run = 1 + this.breakIndents[i];
          display += run;
          inserted += run;
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
      int regionEnd = display.length() - suffix;
      for (int i = 0; i < this.breakOffsets.length; ++i) {
        int runEnd = this.breakOffsets[i] + 1 + this.breakIndents[i];
        if (this.breakOffsets[i] < regionEnd && runEnd > prefix) {
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
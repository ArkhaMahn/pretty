package org.zaproxy.zap.extension.prettyview.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.zaproxy.zap.extension.prettyview.detect.PayloadFormat;

public final class LargePayloadPolicy {
  public static final int SYNTAX_OFF_LIMIT_CHARS = 0x500000;
  /** Past this longest line, wrapping stops looking on word boundaries. See {@link #shouldWrap}. */
  public static final int MAX_WORD_WRAP_LINE_CHARS = 20000;
  /**
   * Past this longest line, syntax highlighting is dropped.
   *
   * <p>The editor re-tokenizes the whole logical line a painted row belongs to, so the cost of a repaint
   * is set by the longest line rather than by the part of it on screen. A 118 871-char prettified JSON
   * response holding one 54 332-char line repainted in 132 ms median against a 16 ms frame, which is what
   * scrolling stuttering looks like; the same bytes with that one line split into 1 000-char lines
   * repainted in 3 ms.
   *
   * <p>In practice the displayed text is split before it gets here, so no line reaches this limit and a
   * long payload keeps its highlighting. The limit stays as the guard for text that reaches the editor
   * unsplit, which is why callers pass the text they are about to display rather than the formatted text.
   */
  public static final int MAX_SYNTAX_LINE_CHARS = 20000;

  private LargePayloadPolicy() {
  }

  /**
   * Always true: lines are never left unwrapped.
   *
   * <p>An unwrapped line is not merely a line you have to scroll sideways. Long enough, it becomes a row
   * that is wider than the component by orders of magnitude, and the editor then fails to paint all of it.
   * On a 138,878 character JSON response whose prettified form holds one 54,332 character line, switching
   * wrapping off left the editor asking for a preferred width of 543,311 pixels, and that row drew 12,581
   * pixels of ink against 27,517 for the same characters wrapped. Most of the visible line was simply not
   * painted, which is what reads on screen as garbled text.
   *
   * <p>So the size limit below no longer decides whether to wrap. It decides how.
   */
  public static boolean shouldWrap(String text) {
    return true;
  }

  /**
   * Whether to wrap on word boundaries rather than anywhere.
   *
   * <p>Word wrapping has to search each line for break opportunities, and a line with no spaces to break
   * at is the worst case, so past the limit the line is wrapped at any character instead. That is cheap,
   * and it renders the same characters identically. Callers pass the text they are about to display, which
   * {@link DisplayLineSplitter} has already broken into short lines, so in practice this is true and long
   * payloads still break on word boundaries.
   */
  public static boolean shouldWrapOnWordBoundaries(String text) {
    return LargePayloadPolicy.longestLineLength(text) <= LargePayloadPolicy.MAX_WORD_WRAP_LINE_CHARS;
  }

  public static int longestLineLength(String text) {
    if (text == null) {
      return 0;
    }
    int longest = 0;
    int start = 0;
    for (int i = 0; i < text.length(); ++i) {
      char c = text.charAt(i);
      if (c != '\n' && c != '\r') continue;
      longest = Math.max(longest, i - start);
      start = i + 1;
    }
    return Math.max(longest, text.length() - start);
  }

  public static String syntaxStyleFor(String text, PayloadFormat format) {
    if (text != null && text.length() > SYNTAX_OFF_LIMIT_CHARS) {
      return "text/plain";
    }
    if (LargePayloadPolicy.longestLineLength(text) > LargePayloadPolicy.MAX_SYNTAX_LINE_CHARS) {
      return "text/plain";
    }
    return SyntaxStyleMapper.syntaxStyleOf(format);
  }

  /**
   * The style for a payload that is about to be displayed as {@code split}.
   *
   * <p>The two limits answer different questions and so are asked of different texts. The character limit
   * is about the size of the payload, so it is asked of the formatted text: the displayed text is the same
   * characters plus the seams {@link DisplayLineSplitter} added, and a payload sitting within a few
   * hundred characters of the limit would cross it on seams alone and lose its highlighting for no reason.
   * The line limit is about what the editor has to tokenize, so it is asked of the displayed text, which
   * is the only text the editor ever holds.
   */
  static String syntaxStyleFor(DisplayLineSplitter.Split split, PayloadFormat format) {
    if (split.originalText().length() > SYNTAX_OFF_LIMIT_CHARS) {
      return "text/plain";
    }
    if (LargePayloadPolicy.longestLineLength(split.displayText()) > MAX_SYNTAX_LINE_CHARS) {
      return "text/plain";
    }
    return SyntaxStyleMapper.syntaxStyleOf(format);
  }

  public static void applyBaseline(RSyntaxTextArea area) {
    area.setTabsEmulated(true);
    area.setTabSize(4);
    area.setCodeFoldingEnabled(false);
    area.setHighlightCurrentLine(false);
    area.setMarkOccurrences(false);
    area.setHyperlinksEnabled(false);
    area.setClearWhitespaceLinesEnabled(false);
    area.setHighlightSecondaryLanguages(false);
    area.setUseFocusableTips(false);
  }

  public static void applyForSize(RSyntaxTextArea area, String text) {
    area.setLineWrap(true);
    area.setWrapStyleWord(LargePayloadPolicy.shouldWrapOnWordBoundaries(text));
    area.setAntiAliasingEnabled(true);
  }

  /**
   * The editor settings for a payload that is about to be displayed as {@code split}.
   *
   * <p>Wrapping is judged on the displayed text, because that is the text whose lines the editor lays out.
   */
  static void applyForSize(RSyntaxTextArea area, DisplayLineSplitter.Split split) {
    area.setLineWrap(true);
    area.setWrapStyleWord(LargePayloadPolicy.shouldWrapOnWordBoundaries(split.displayText()));
    area.setAntiAliasingEnabled(true);
  }
}


package org.zaproxy.zap.extension.prettyview.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.zaproxy.zap.extension.prettyview.detect.PayloadFormat;

public final class LargePayloadPolicy {
  /**
   * Formatted size past which highlighting is dropped. Ten megabytes covers what a 2.5 MB
   * minified JSON payload unfolds into (~9 MB over ~380 k lines, measured live with stable
   * paints); past that the token heap for millions of tokens is not worth the colours, and the
   * view renders plain instead. Asked of the formatted text, whose expansion is what actually
   * gets tokenized - not of the raw body.
   */
  public static final int SYNTAX_OFF_LIMIT_CHARS = 0xA00000;
  /** Past this longest line, wrapping stops looking on word boundaries. See {@link #shouldWrap}. */
  public static final int MAX_WORD_WRAP_LINE_CHARS = 20000;
  /**
   * Past this longest line, highlighting is dropped for that line by the token maker: lexing
   * tens of thousands of characters on every scroll is what reads as scroll lag.
   *
   * <p>The editor re-tokenizes the whole logical line a painted row belongs to, so the cost of a
   * repaint is set by the longest line rather than the part of it on screen.
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
   * and it renders the same characters identically.
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
    // Monospaced glyphs are not all the same width, so letting the editor measure them fractionally keeps
    // the characters evenly spaced instead of snapping each one to a whole pixel. RSTA only turns this on
    // by itself on some platforms; asking for it explicitly makes the text look the same everywhere.
    area.setFractionalFontMetricsEnabled(true);
  }

  /**
   * Editor settings for a payload about to be displayed: wrapping follows the displayed text,
   * because that is the text whose lines the editor lays out.
   */
  static void applyForSize(RSyntaxTextArea area, String text) {
    area.setLineWrap(true);
    area.setWrapStyleWord(LargePayloadPolicy.shouldWrapOnWordBoundaries(text));
    area.setAntiAliasingEnabled(true);
  }
}


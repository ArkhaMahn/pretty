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
  /**
   * Past this longest line, syntax highlighting is dropped.
   *
   * <p>The editor re-tokenizes the whole logical line a painted row belongs to, so the cost of a repaint
   * is set by the longest line rather than by the part of it on screen. A 118 871-char prettified JSON
   * response holding one 54 332-char line repainted in 132 ms median against a 16 ms frame, which is what
   * scrolling stuttering looks like.
   *
   * <p>In practice the displayed text is split before it gets here, so no line reaches this limit and a
   * long payload keeps its highlighting. The limit stays as the guard for text that reaches the editor
   * unsplit, which is why callers pass the text they are about to display rather than the formatted text.
   */
  public static final int MAX_SYNTAX_LINE_CHARS = 20000;

  private LargePayloadPolicy() {
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
    area.setCodeFoldingEnabled(true);
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
   * The editor settings for a payload: wrapping follows the View menu toggle (on by default),
   * folding fills the gap when wrapping is off.
   *
   * <p>Lines wrap in the editor instead of scrolling sideways, and folding is disabled while
   * they do: a fold gutter cannot share its lines with wrapped rows, so Burp drops folding the
   * moment wrapping goes on, and this does the same. Monster lines never reach the editor
   * unwrapped - {@link DisplayLineSplitter} breaks those first - so no row is wide enough to
   * mispaint either way.
   */
  public static void applyForSize(RSyntaxTextArea area, String text) {
    boolean wrap = PrettyLineWrap.isEnabled();
    area.setLineWrap(wrap);
    // Burp's wrapper asks its editor for the same preference: break wrapped rows on word
    // boundaries rather than mid-word.
    area.setWrapStyleWord(true);
    area.setCodeFoldingEnabled(!wrap);
    area.setAntiAliasingEnabled(true);
  }

  /**
   * The editor settings for a payload that is about to be displayed as {@code split}.
   *
   * <p>Same as {@link #applyForSize(RSyntaxTextArea, String)}: the split only ever shortens monster
   * lines, so the wrap setting is judged on text the editor can lay out either way.
   */
  static void applyForSize(RSyntaxTextArea area, DisplayLineSplitter.Split split) {
    boolean wrap = PrettyLineWrap.isEnabled();
    area.setLineWrap(wrap);
    area.setWrapStyleWord(true);
    area.setCodeFoldingEnabled(!wrap);
    area.setAntiAliasingEnabled(true);
  }
}


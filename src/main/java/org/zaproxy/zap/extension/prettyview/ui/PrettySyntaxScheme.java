package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.Color;
import java.awt.Font;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;

final class PrettySyntaxScheme {
  /** Foreground used by the dark palette, and the fallback if a palette is not supplied. */
  static final Color FOREGROUND = new Color(216, 216, 216);

  private PrettySyntaxScheme() {
  }

  /**
   * The token colours for a palette.
   *
   * <p>The editor is themed twice over: the widget chrome follows {@link EditorTheme.Palette}, and every
   * token type is given its own colour here. Both halves have to move together - a dark token scheme on a
   * white background is the same defect as a dark background under black text - so this reads the palette
   * rather than assuming one. The hues are the usual ones for each side of the line: bright cyan, tan and
   * violet tokens on dark, the deeper blue, maroon and green of a printed page on light.
   */
  static SyntaxScheme create(Font font, EditorTheme.Palette palette) {
    boolean dark = palette == null || palette.isDark();
    Color foreground = dark ? FOREGROUND : palette.getForeground();
    Color primary = dark ? new Color(82, 207, 253) : new Color(0, 0, 192);
    Color string = dark ? new Color(206, 145, 120) : new Color(163, 21, 21);
    Color number = dark ? new Color(197, 134, 192) : new Color(9, 134, 88);
    Color keyword = dark ? new Color(220, 220, 170) : new Color(0, 0, 255);
    Color muted = dark ? new Color(126, 146, 136) : new Color(0, 128, 0);
    Color error = dark ? new Color(244, 71, 71) : new Color(204, 0, 0);

    SyntaxScheme scheme = new SyntaxScheme(font, false);
    scheme.setStyle(0, style(foreground, font));
    scheme.setStyle(1, style(muted, font));
    scheme.setStyle(2, style(muted, font));
    scheme.setStyle(3, style(muted, font));
    scheme.setStyle(4, style(muted, font));
    scheme.setStyle(5, style(muted, font));
    scheme.setStyle(6, style(keyword, font));
    scheme.setStyle(7, style(keyword, font));
    scheme.setStyle(8, style(primary, font));
    scheme.setStyle(9, style(keyword, font));
    scheme.setStyle(10, style(number, font));
    scheme.setStyle(11, style(number, font));
    scheme.setStyle(12, style(number, font));
    scheme.setStyle(13, style(string, font));
    scheme.setStyle(14, style(string, font));
    scheme.setStyle(15, style(string, font));
    scheme.setStyle(16, style(primary, font));
    scheme.setStyle(17, style(primary, font));
    scheme.setStyle(18, style(muted, font));
    scheme.setStyle(19, style(muted, font));
    scheme.setStyle(20, style(foreground, font));
    scheme.setStyle(21, style(foreground, font));
    scheme.setStyle(22, style(foreground, font));
    scheme.setStyle(23, style(foreground, font));
    scheme.setStyle(24, style(keyword, font));
    scheme.setStyle(25, style(foreground, font));
    scheme.setStyle(26, style(primary, font));
    scheme.setStyle(27, style(foreground, font));
    scheme.setStyle(28, style(string, font));
    scheme.setStyle(29, style(muted, font));
    scheme.setStyle(30, style(muted, font));
    scheme.setStyle(31, style(muted, font));
    scheme.setStyle(32, style(string, font));
    scheme.setStyle(33, style(string, font));
    scheme.setStyle(34, style(string, font));
    // 35 ERROR_IDENTIFIER and 36 ERROR_NUMBER_FORMAT are the two token types the tokenizer emits for text
    // it cannot place. They used to be painted as strings, which made a malformed value look like a quoted
    // one; they get their own colour so a tokenizer error reads as an error. The unterminated string
    // tokens (37/38) stay in the string colour, because for a string continued onto the next display line
    // that is exactly what they are.
    scheme.setStyle(35, style(error, font));
    scheme.setStyle(36, style(error, font));
    scheme.setStyle(37, style(string, font));
    scheme.setStyle(38, style(string, font));
    return scheme;
  }

  private static Style style(Color foreground, Font font) {
    Style style = new Style(foreground, null, font, false);
    style.underline = false;
    return style;
  }

  static void apply(RSyntaxTextArea area, Font font, EditorTheme.Palette palette) {
    area.setSyntaxScheme(PrettySyntaxScheme.create(font, palette));
  }
}

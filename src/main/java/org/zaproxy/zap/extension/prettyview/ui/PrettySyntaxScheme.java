package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.Color;
import java.awt.Font;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;

final class PrettySyntaxScheme {
  /** Foreground used by the dark palette, and the fallback if a palette is not supplied. */
  static final Color FOREGROUND = new Color(206, 206, 206);

  private PrettySyntaxScheme() {
  }

  /**
   * The token colours for a palette.
   *
   * <p>The hues follow Burp's message editor exactly (see {@code burp/theme/DarkTheme.json} and
   * {@code LightTheme.json} in the reference install): orange reserved words, green strings, blue
   * numbers, gold tag names, pale-blue operators on dark; navy keywords, green strings, blue
   * numbers, purple tags on light. Both halves have to move together - a dark token scheme on a
   * white background is the same defect as a dark background under black text - so this reads the
   * palette rather than assuming one.
   */
  static SyntaxScheme create(Font font, EditorTheme.Palette palette) {
    boolean dark = palette == null || palette.isDark();
    Color text = dark ? new Color(206, 206, 206) : new Color(20, 20, 20);
    Color keyword = dark ? new Color(255, 158, 87) : new Color(0, 0, 117);
    Color string = dark ? new Color(147, 199, 99) : new Color(0, 128, 0);
    Color number = dark ? new Color(121, 193, 244) : new Color(0, 0, 255);
    Color comment = dark ? new Color(163, 186, 186) : new Color(0, 90, 0);
    Color tagName = dark ? new Color(233, 192, 99) : new Color(176, 0, 192);
    Color operator = dark ? new Color(209, 232, 249) : new Color(32, 32, 32);
    Color preprocessor = dark ? new Color(160, 130, 189) : new Color(0, 90, 0);
    Color cdata = dark ? new Color(213, 230, 240) : new Color(204, 102, 0);
    Color entity = dark ? new Color(103, 140, 177) : new Color(0, 128, 0);
    Color error = dark ? new Color(244, 71, 71) : new Color(204, 0, 0);

    SyntaxScheme scheme = new SyntaxScheme(font, false);
    scheme.setStyle(0, style(text, font));
    scheme.setStyle(1, style(comment, font));
    scheme.setStyle(2, style(comment, font));
    scheme.setStyle(3, style(comment, font));
    scheme.setStyle(4, style(comment, font));
    scheme.setStyle(5, style(comment, font));
    scheme.setStyle(6, style(keyword, font));
    scheme.setStyle(7, style(keyword, font));
    scheme.setStyle(8, style(tagName, font));
    scheme.setStyle(9, style(keyword, font));
    scheme.setStyle(10, style(number, font));
    scheme.setStyle(11, style(number, font));
    scheme.setStyle(12, style(number, font));
    scheme.setStyle(13, style(string, font));
    scheme.setStyle(14, style(string, font));
    scheme.setStyle(15, style(string, font));
    scheme.setStyle(16, style(keyword, font));
    scheme.setStyle(17, style(tagName, font));
    scheme.setStyle(18, style(operator, font));
    // 19 is borrowed for HTTP header names and the request/status line, which Burp paints
    // pale-blue (dark) and navy (light) - the same hues its operators use.
    scheme.setStyle(19, style(operator, font));
    scheme.setStyle(20, style(text, font));
    scheme.setStyle(21, style(text, font));
    scheme.setStyle(22, style(operator, font));
    scheme.setStyle(23, style(operator, font));
    scheme.setStyle(24, style(preprocessor, font));
    scheme.setStyle(25, style(text, font));
    scheme.setStyle(26, style(tagName, font));
    scheme.setStyle(27, style(text, font));
    scheme.setStyle(28, style(string, font));
    scheme.setStyle(29, style(comment, font));
    // 30 is the doctype, which Burp paints as a comment.
    scheme.setStyle(30, style(comment, font));
    scheme.setStyle(31, style(preprocessor, font));
    scheme.setStyle(32, style(cdata, font));
    scheme.setStyle(33, style(cdata, font));
    scheme.setStyle(34, style(entity, font));
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

package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.Color;
import java.awt.Font;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;

final class PrettySyntaxScheme {
  static final Color FOREGROUND = new Color(216, 216, 216);
  private static final Color PRIMARY = new Color(130, 207, 253);
  private static final Color STRING_VALUE = new Color(206, 145, 120);
  private static final Color NUMERIC = new Color(197, 134, 192);
  private static final Color KEYWORD_LITERAL = new Color(220, 220, 170);
  private static final Color MUTED = new Color(126, 146, 136);

  private PrettySyntaxScheme() {
  }

  static SyntaxScheme create(Font font) {
    SyntaxScheme scheme = new SyntaxScheme(font, false);
    PrettySyntaxScheme.paint(scheme, 0, FOREGROUND, font);
    PrettySyntaxScheme.paint(scheme, 21, FOREGROUND, font);
    PrettySyntaxScheme.paint(scheme, 26, PRIMARY, font);
    PrettySyntaxScheme.paint(scheme, 17, PRIMARY, font);
    PrettySyntaxScheme.paint(scheme, 16, PRIMARY, font);
    PrettySyntaxScheme.paint(scheme, 8, PRIMARY, font);
    PrettySyntaxScheme.paint(scheme, 27, FOREGROUND, font);
    PrettySyntaxScheme.paint(scheme, 20, FOREGROUND, font);
    PrettySyntaxScheme.paint(scheme, 13, STRING_VALUE, font);
    PrettySyntaxScheme.paint(scheme, 15, STRING_VALUE, font);
    PrettySyntaxScheme.paint(scheme, 14, STRING_VALUE, font);
    PrettySyntaxScheme.paint(scheme, 28, STRING_VALUE, font);
    PrettySyntaxScheme.paint(scheme, 33, STRING_VALUE, font);
    PrettySyntaxScheme.paint(scheme, 34, STRING_VALUE, font);
    PrettySyntaxScheme.paint(scheme, 32, STRING_VALUE, font);
    PrettySyntaxScheme.paint(scheme, 10, NUMERIC, font);
    PrettySyntaxScheme.paint(scheme, 11, NUMERIC, font);
    PrettySyntaxScheme.paint(scheme, 12, NUMERIC, font);
    PrettySyntaxScheme.paint(scheme, 9, KEYWORD_LITERAL, font);
    PrettySyntaxScheme.paint(scheme, 6, KEYWORD_LITERAL, font);
    PrettySyntaxScheme.paint(scheme, 7, KEYWORD_LITERAL, font);
    PrettySyntaxScheme.paint(scheme, 24, KEYWORD_LITERAL, font);
    PrettySyntaxScheme.paint(scheme, 1, MUTED, font);
    PrettySyntaxScheme.paint(scheme, 2, MUTED, font);
    PrettySyntaxScheme.paint(scheme, 3, MUTED, font);
    PrettySyntaxScheme.paint(scheme, 4, MUTED, font);
    PrettySyntaxScheme.paint(scheme, 5, MUTED, font);
    PrettySyntaxScheme.paint(scheme, 29, MUTED, font);
    PrettySyntaxScheme.paint(scheme, 30, MUTED, font);
    PrettySyntaxScheme.paint(scheme, 31, MUTED, font);
    PrettySyntaxScheme.paint(scheme, 19, MUTED, font);
    PrettySyntaxScheme.paint(scheme, 18, MUTED, font);
    PrettySyntaxScheme.paint(scheme, 22, FOREGROUND, font);
    PrettySyntaxScheme.paint(scheme, 23, FOREGROUND, font);
    PrettySyntaxScheme.paint(scheme, 25, FOREGROUND, font);
    PrettySyntaxScheme.paint(scheme, 35, STRING_VALUE, font);
    PrettySyntaxScheme.paint(scheme, 36, STRING_VALUE, font);
    PrettySyntaxScheme.paint(scheme, 37, STRING_VALUE, font);
    PrettySyntaxScheme.paint(scheme, 38, STRING_VALUE, font);
    return scheme;
  }

  private static void paint(SyntaxScheme scheme, int tokenType, Color color, Font font) {
    scheme.setStyle(tokenType, PrettySyntaxScheme.style(color, font));
  }

  private static Style style(Color foreground, Font font) {
    Style style = new Style(foreground, null, font, false);
    style.underline = false;
    return style;
  }

  static void apply(RSyntaxTextArea area, Font font) {
    area.setSyntaxScheme(PrettySyntaxScheme.create(font));
  }
}


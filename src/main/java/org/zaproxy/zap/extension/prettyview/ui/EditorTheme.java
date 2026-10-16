package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.Color;
import java.awt.Font;
import javax.swing.JScrollPane;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.zaproxy.zap.utils.DisplayUtils;
import org.zaproxy.zap.utils.FontUtils;
import org.zaproxy.zap.utils.FontUtils.FontType;

final class EditorTheme {
  /** Font used only when ZAP's font configuration cannot be read, which is outside a running ZAP. */
  private static final String FALLBACK_FONT_FAMILY = "Monospaced";

  private static final int FALLBACK_FONT_SIZE = 18;
  static final Palette LIGHT = new Palette("light", new Color(251, 251, 251), new Color(20, 20, 20), new Color(235, 241, 246), new Color(20, 20, 20), new Color(43, 43, 43),
      new Color(120, 120, 120), new Color(120, 120, 120), new Color(225, 224, 224), new Color(234, 234, 255), new Color(0, 0, 128),
      new Color(221, 221, 221), new Color(255, 255, 0));
  static final Palette DARK = new Palette("dark", new Color(43, 43, 43), PrettySyntaxScheme.FOREGROUND, new Color(83, 132, 178), new Color(255, 255, 255), new Color(197, 199, 200),
      new Color(160, 160, 160), new Color(160, 160, 160), new Color(72, 76, 83), new Color(58, 61, 65), new Color(110, 110, 110),
      new Color(85, 85, 85), new Color(0, 16, 103));
  static final Color SEARCH_HIGHLIGHT = new Color(0, 122, 204);

  private EditorTheme() {
  }

  private static String describe(Color color) {
    return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
  }

  static Palette palette() {
    // ZAP ships a light and a dark look and feel and lets the user switch between them while running, so
    // the panel is asked for its palette each time it is themed rather than being told once. Asking ZAP
    // rather than guessing keeps the editor on the same side of the light/dark line as the rest of the
    // window: a dark editor inside a light ZAP is the seam this is here to avoid. Outside a running ZAP
    // there is no theme to read, and the dark palette is kept as the default.
    try {
      return DisplayUtils.isDarkLookAndFeel() ? DARK : LIGHT;
    } catch (RuntimeException | LinkageError e) {
      return DARK;
    }
  }

  /**
   * The font this panel renders in: a monospace font, like Burp's message editor, unless the
   * user picked a work-panel font under ZAP's Configure Fonts, which is then honoured as-is.
   * The size always follows ZAP's setting, so display scaling keeps working; only an
   * unconfigured family falls back to monospace rather than ZAP's proportional UI font, under
   * which columns, tables and indentation would not line up.
   */
  static Font configuredFont() {
    try {
      if (FontUtils.isDefaultFontSet(FontType.workPanels)) {
        return FontUtils.getFontWithFallback(FontType.workPanels, FALLBACK_FONT_FAMILY);
      }
      return new Font(FALLBACK_FONT_FAMILY, Font.PLAIN,
          FontUtils.getFont(FontType.workPanels).getSize());
    } catch (RuntimeException | LinkageError e) {
      return new Font(FALLBACK_FONT_FAMILY, Font.PLAIN, FALLBACK_FONT_SIZE);
    }
  }

  static void apply(RSyntaxTextArea area, JScrollPane pane, Palette palette) {
    area.setBackground(palette.getBackground());
    area.setForeground(palette.getForeground());
    area.setCaretColor(palette.getCaret());
    area.setSelectedTextColor(palette.getForeground());
    area.setSelectionColor(palette.getSelectionBackground());
    area.setMarkOccurrencesColor(SEARCH_HIGHLIGHT);
    area.setMarkAllHighlightColor(palette.getSearchMark());
    area.setMatchedBracketBGColor(palette.getBracketBackground());
    area.setMatchedBracketBorderColor(palette.getBracketBorder());
    area.setCurrentLineHighlightColor(palette.getBackground());
    Font font = EditorTheme.configuredFont();
    area.setFont(font);
    PrettySyntaxScheme.apply(area, font, palette);
    if (pane != null) {
      pane.setViewportBorder(null);
      pane.setBorder(null);
      pane.getViewport().setBackground(palette.getBackground());
      pane.setBackground(palette.getBackground());
      if (pane instanceof org.fife.ui.rtextarea.RTextScrollPane) {
        org.fife.ui.rtextarea.Gutter gutter =
            ((org.fife.ui.rtextarea.RTextScrollPane) pane).getGutter();
        if (gutter != null) {
          gutter.setBackground(palette.getBackground());
          gutter.setBorderColor(palette.getGutterBorder());
          gutter.setLineNumberColor(palette.getLineNumber());
          gutter.setLineNumberFont(font);
          gutter.setCurrentLineNumberColor(palette.getForeground());
          gutter.setFoldBackground(palette.getBackground());
          gutter.setFoldIndicatorForeground(palette.getFoldIcon());
          gutter.setFoldIndicatorArmedForeground(palette.getForeground());
          gutter.setArmedFoldBackground(palette.getFoldArmedBackground());
          // No collapsed-region previews on hover: the fold gutter shows the folds, never
          // their hidden text.
          for (java.awt.Component child : gutter.getComponents()) {
            if (child instanceof org.fife.ui.rtextarea.FoldIndicator) {
              ((org.fife.ui.rtextarea.FoldIndicator) child).setShowCollapsedRegionToolTips(false);
            }
          }
        }
      }
    }
    area.repaint();
  }

  static final class Palette {
    private final String name;
    private final Color background;
    private final Color foreground;
    private final Color selectionBackground;
    private final Color selectionForeground;
    private final Color caret;
    private final Color lineNumber;
    private final Color foldIcon;
    private final Color foldArmedBackground;
    private final Color bracketBackground;
    private final Color bracketBorder;
    private final Color gutterBorder;
    private final Color searchMark;

    private Palette(String name, Color background, Color foreground, Color selectionBackground, Color selectionForeground, Color caret,
        Color lineNumber, Color foldIcon, Color foldArmedBackground, Color bracketBackground, Color bracketBorder,
        Color gutterBorder, Color searchMark) {
      this.name = name;
      this.background = background;
      this.foreground = foreground;
      this.selectionBackground = selectionBackground;
      this.selectionForeground = selectionForeground;
      this.caret = caret;
      this.lineNumber = lineNumber;
      this.foldIcon = foldIcon;
      this.foldArmedBackground = foldArmedBackground;
      this.bracketBackground = bracketBackground;
      this.bracketBorder = bracketBorder;
      this.gutterBorder = gutterBorder;
      this.searchMark = searchMark;
    }

    String getName() {
      return this.name;
    }

    /** Whether this palette paints light text on a dark background. */
    boolean isDark() {
      return "dark".equals(this.name);
    }

    Color getBackground() {
      return this.background;
    }

    Color getForeground() {
      return this.foreground;
    }

    Color getLineNumber() {
      return this.lineNumber;
    }

    Color getFoldIcon() {
      return this.foldIcon;
    }

    Color getFoldArmedBackground() {
      return this.foldArmedBackground;
    }

    Color getBracketBackground() {
      return this.bracketBackground;
    }

    Color getBracketBorder() {
      return this.bracketBorder;
    }

    Color getGutterBorder() {
      return this.gutterBorder;
    }

    Color getSearchMark() {
      return this.searchMark;
    }

    Color getCaret() {
      return this.caret;
    }

    Color getSelectionBackground() {
      return this.selectionBackground;
    }

    Color getSelectionForeground() {
      return this.selectionForeground;
    }

    public String toString() {
      return this.name + " (" + EditorTheme.describe(this.background) + " background, " + EditorTheme.describe(this.foreground) + " text)";
    }
  }
}


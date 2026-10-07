package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.Color;
import java.awt.Font;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JScrollPane;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.zaproxy.zap.utils.FontUtils;
import org.zaproxy.zap.utils.FontUtils.FontType;

final class EditorTheme {
  /** Font used only when ZAP's font configuration cannot be read, which is outside a running ZAP. */
  private static final String FALLBACK_FONT_FAMILY = "Monospaced";

  private static final int FALLBACK_FONT_SIZE = 18;
  static final Palette LIGHT = new Palette("light", new Color(255, 255, 255), new Color(0, 0, 0), new Color(216, 228, 240), new Color(0, 0, 0), new Color(0, 0, 0));
  static final Palette DARK = new Palette("dark", new Color(47, 47, 47), PrettySyntaxScheme.FOREGROUND, new Color(38, 79, 120), PrettySyntaxScheme.FOREGROUND, new Color(174, 175, 173));
  static final Color SEARCH_HIGHLIGHT = new Color(0, 122, 204);

  private EditorTheme() {
  }

  private static String describe(Color color) {
    return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
  }

  static Palette palette() {
    return DARK;
  }

  /**
   * The font ZAP is configured to use for work panels, which is where this panel lives.
   *
   * <p>This is the same call ZAP makes for its own syntax highlighted text area, so this panel follows the
   * font the user picked under Configure Fonts, at the same size, and falls back to ZAP's own default font
   * when they have not picked one. Asking for a font is not free to get wrong: ZAP's font map is empty
   * until the look and feel publishes a default font, and reading it before then throws, so a failure here
   * falls back to a monospaced font rather than taking the panel down with it.
   */
  static Font configuredFont() {
    try {
      return FontUtils.getFontWithFallback(FontType.workPanels,
          FontUtils.getFont(FontType.workPanels).getFontName());
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
    area.setCurrentLineHighlightColor(palette.getBackground());
    Font font = EditorTheme.configuredFont();
    area.setFont(font);
    PrettySyntaxScheme.apply(area, font);
    if (pane != null) {
      pane.setViewportBorder(null);
      pane.setBorder(null);
      pane.getViewport().setBackground(palette.getBackground());
      pane.setBackground(palette.getBackground());
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

    private Palette(String name, Color background, Color foreground, Color selectionBackground, Color selectionForeground, Color caret) {
      this.name = name;
      this.background = background;
      this.foreground = foreground;
      this.selectionBackground = selectionBackground;
      this.selectionForeground = selectionForeground;
      this.caret = caret;
    }

    String getName() {
      return this.name;
    }

    Color getBackground() {
      return this.background;
    }

    Color getForeground() {
      return this.foreground;
    }

    Map<String, Object> asLookAndFeelKeys() {
      LinkedHashMap<String, Serializable> keys = new LinkedHashMap<String, Serializable>();
      keys.put("RTextArea.background", this.background);
      keys.put("RTextArea.foreground", this.foreground);
      keys.put("RTextArea.inactiveForeground", this.foreground);
      keys.put("RTextArea.caretForeground", this.caret);
      keys.put("RTextArea.selectedTextForeground", this.selectionForeground);
      keys.put("RTextArea.secondaryBackground", this.background);
      keys.put("RTextArea.gutter.background", this.background);
      keys.put("RTextArea.lineNumber.background", this.background);
      keys.put("RTextArea.lineNumber.foreground", this.foreground);
      keys.put("RTextArea.lineNumber.currentLineForeground", this.foreground);
      keys.put("RTextArea.zoomGutter.background", this.background);
      keys.put("RTextArea.zoomGutter.foreground", this.foreground.darker());
      keys.put("RTextArea.overviewRuler.background", this.background);
      keys.put("RTextArea.overviewRuler.foreground", this.foreground.darker());
      keys.put("RTextArea.overviewRuler.thickCaretForeground", this.foreground);
      keys.put("RTextArea.overviewRuler.thinCaretForeground", this.foreground.darker());
      keys.put("RTextArea.borderColor", this.background);
      keys.put("RTextArea.font", EditorTheme.configuredFont());
      keys.put("ScrollPane.background", this.background);
      keys.put("Viewport.background", this.background);
      return Collections.unmodifiableMap(keys);
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


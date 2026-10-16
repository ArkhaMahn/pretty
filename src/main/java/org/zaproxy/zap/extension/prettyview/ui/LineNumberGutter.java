package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import javax.swing.JComponent;
import javax.swing.JTextPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;

/**
 * Line numbers for the Pretty editor, painted in the scroll pane's row header.
 *
 * <p>Numbers count document lines one by one: the editor holds exactly the formatted text, so
 * no mapping is ever needed. The gutter follows scrolling, edits and loads, and repaints only
 * itself.
 */
final class LineNumberGutter extends JComponent {
  private static final long serialVersionUID = 1L;

  /** Pixels between the rightmost digit and the editor. */
  private static final int RIGHT_GAP = 8;
  /** Pixels between the gutter edge and the leftmost digit. */
  private static final int LEFT_GAP = 10;

  private final JTextPane textPane;
  private Font gutterFont;
  private Color numberColor;
  private Color background;

  LineNumberGutter(JTextPane textPane) {
    this.textPane = textPane;
    this.setOpaque(true);
    JViewport viewport = LineNumberGutter.viewportOf(textPane);
    if (viewport != null) {
      viewport.addChangeListener(event -> this.repaint());
    }
    textPane.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent event) {
        LineNumberGutter.this.onDocumentChanged();
      }

      @Override
      public void removeUpdate(DocumentEvent event) {
        LineNumberGutter.this.onDocumentChanged();
      }

      @Override
      public void changedUpdate(DocumentEvent event) {
        LineNumberGutter.this.repaint();
      }
    });
    // A swapped document brings its own listeners; the gutter follows the swap.
    textPane.addPropertyChangeListener("document", event -> {
      Object fresh = event.getNewValue();
      if (fresh instanceof javax.swing.text.Document) {
        ((javax.swing.text.Document) fresh).addDocumentListener(new DocumentListener() {
          @Override
          public void insertUpdate(DocumentEvent inner) {
            LineNumberGutter.this.onDocumentChanged();
          }

          @Override
          public void removeUpdate(DocumentEvent inner) {
            LineNumberGutter.this.onDocumentChanged();
          }

          @Override
          public void changedUpdate(DocumentEvent inner) {
            LineNumberGutter.this.repaint();
          }
        });
        LineNumberGutter.this.onDocumentChanged();
      }
    });
  }

  /** Applies the editor palette: numbers, background and the editor's own font. */
  void applyTheme(Font font, Color numbers, Color background) {
    this.gutterFont = font;
    this.numberColor = numbers;
    this.background = background;
    this.setBackground(background);
    this.onDocumentChanged();
  }

  private void onDocumentChanged() {
    this.updateWidth();
    this.repaint();
  }

  private static JViewport viewportOf(JTextPane pane) {
    java.awt.Container parent = pane.getParent();
    return parent instanceof JViewport ? (JViewport) parent : null;
  }

  /** Widens the gutter to the current line count. */
  private void updateWidth() {
    Element root = this.textPane.getDocument().getDefaultRootElement();
    int lines = Math.max(1, root.getElementCount());
    Font font = this.gutterFont != null ? this.gutterFont : this.textPane.getFont();
    FontMetrics metrics = this.getFontMetrics(font);
    int width = LEFT_GAP + metrics.stringWidth(Integer.toString(lines)) + RIGHT_GAP;
    Dimension size = new Dimension(width, Integer.MAX_VALUE);
    this.setPreferredSize(size);
    this.setSize(size);
    if (this.getParent() != null) {
      this.getParent().revalidate();
    }
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    if (this.background != null) {
      graphics.setColor(this.background);
      graphics.fillRect(0, 0, this.getWidth(), this.getHeight());
    }
    Font font = this.gutterFont != null ? this.gutterFont : this.textPane.getFont();
    graphics.setFont(font);
    graphics.setColor(this.numberColor != null ? this.numberColor : this.textPane.getForeground());
    FontMetrics metrics = graphics.getFontMetrics(font);
    Rectangle clip = graphics.getClipBounds();
    Element root = this.textPane.getDocument().getDefaultRootElement();
    int first = root.getElementIndex(this.textPane.viewToModel(new Point(0, clip.y)));
    int last = root.getElementIndex(this.textPane.viewToModel(
        new Point(0, clip.y + clip.height)));
    for (int line = Math.max(0, first); line <= Math.min(last, root.getElementCount() - 1); ++line) {
      try {
        int start = root.getElement(line).getStartOffset();
        Rectangle row = this.textPane.modelToView(start);
        if (row == null) {
          continue;
        }
        // The viewport position is subtracted so numbers track scrolling; the row's own
        // height puts the digit on its first visual row even when the line wraps.
        JViewport viewport = LineNumberGutter.viewportOf(this.textPane);
        int viewY = viewport == null ? row.y : row.y - viewport.getViewPosition().y;
        String number = Integer.toString(line + 1);
        int x = this.getWidth() - RIGHT_GAP - metrics.stringWidth(number);
        int y = viewY + metrics.getAscent();
        graphics.drawString(number, x, y);
      } catch (BadLocationException e) {
        // The document changed mid-paint; the next repaint draws the new lines.
      }
    }
  }
}

package org.zaproxy.zap.extension.prettyview.ui;

import java.util.function.BooleanSupplier;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.DocumentFilter;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import org.zaproxy.zap.extension.prettyview.detect.PayloadFormat;

/**
 * Builds the editor document off the EDT and installs it in one step.
 *
 * <p>Inserting into an attached document re-lays-out the view on every chunk, so the text goes
 * into a detached styled document on the worker thread together with its highlighting, and the
 * finished document is installed on the EDT. A stale ticket abandons the build at the next
 * slice boundary.
 */
final class StyledTextLoader {
  /** Characters inserted per slice while building. */
  private static final int SLICE_CHARS = 0x2000;

  /**
   * Formatted length past which the view streams: the head installs and paints instantly while
   * the tail appends slice by slice, so the UI never blocks on a full first layout. Smaller
   * payloads install in one step.
   */
  static final int STREAM_CHARS = 32768;
  /** Head size for a streaming install: paints in milliseconds. */
  static final int HEAD_CHARS = 8192;
  /** Tail slice size for a streaming install: small enough to keep the UI alive. */
  static final int TAIL_SLICE_CHARS = 0x2000;

  private StyledTextLoader() {
  }

  /**
   * Builds a highlighted document, or null when it goes stale. Highlighting runs here on the
   * worker thread because a large payload lexes for hundreds of milliseconds; the document is
   * detached, so no EDT state is touched.
   */
  static DefaultStyledDocument buildDocument(
      String text, PayloadFormat format, EditorTheme.Palette palette, int fromLine,
      BooleanSupplier stillWanted) {
    return StyledTextLoader.buildHead(text, format, palette, fromLine, Integer.MAX_VALUE,
        stillWanted);
  }

  /**
   * Builds the head of a streaming install: the first {@code headChars} characters with
   * highlighting, for an instant first paint while the tail streams in behind it.
   */
  static DefaultStyledDocument buildHead(
      String text, PayloadFormat format, EditorTheme.Palette palette, int fromLine,
      int headChars, BooleanSupplier stillWanted) {
    String content = text == null ? "" : text;
    String head = content.substring(0, Math.min(headChars, content.length()));
    DefaultStyledDocument document = new DefaultStyledDocument();
    try {
      for (int offset = 0; offset < head.length(); offset += SLICE_CHARS) {
        if (!stillWanted.getAsBoolean()) {
          return null;
        }
        document.insertString(document.getLength(),
            head.substring(offset, Math.min(head.length(), offset + SLICE_CHARS)), null);
      }
    } catch (RuntimeException | BadLocationException e) {
      return null;
    }
    if (!stillWanted.getAsBoolean()) {
      return null;
    }
    try {
      StyledHighlighter highlighter = StyledHighlighter.styles(palette);
      if (!highlighter.highlight(document, format, fromLine, stillWanted)) {
        return null;
      }
    } catch (RuntimeException | BadLocationException e) {
      return null;
    }
    return stillWanted.getAsBoolean() ? document : null;
  }

  /**
   * Swaps the built document into the editor on the EDT, armed for incremental highlighting.
   * Must run on the EDT. Replacing the document costs a single layout pass.
   */
  static void installDocument(PrettyTextPane pane, DefaultStyledDocument document,
      PayloadFormat format, EditorTheme.Palette palette, Runnable onTop, Runnable onDone) {
    pane.setDocument(document);
    document.setDocumentFilter(new HighlightFilter(pane, format, palette));
    onTop.run();
    pane.discardAllEdits();
    onDone.run();
  }

  /**
   * Re-highlights the lines an edit touches, like the regex lexer in Burp's editor: small,
   * synchronous, and limited to the edited lines with carry state recomputed from the top of
   * the affected region. Attribute changes never touch the text, so no recursion is possible.
   */
  private static final class HighlightFilter extends DocumentFilter {
    private final PrettyTextPane pane;
    private final PayloadFormat format;
    private final EditorTheme.Palette palette;

    HighlightFilter(PrettyTextPane pane, PayloadFormat format, EditorTheme.Palette palette) {
      this.pane = pane;
      this.format = format;
      this.palette = palette;
    }

    @Override
    public void insertString(FilterBypass bypass, int offset, String text, AttributeSet attributes)
        throws BadLocationException {
      super.insertString(bypass, offset, text, attributes);
      this.repaintLines(offset, text == null ? 0 : text.length());
    }

    @Override
    public void replace(FilterBypass bypass, int offset, int length, String text,
        AttributeSet attributes) throws BadLocationException {
      super.replace(bypass, offset, length, text, attributes);
      this.repaintLines(offset, text == null ? 0 : text.length());
    }

    @Override
    public void remove(FilterBypass bypass, int offset, int length) throws BadLocationException {
      super.remove(bypass, offset, length);
      this.repaintLines(offset, 0);
    }

    /**
     * Repaints the lines around an edit. Carry state (block comments and the like) flows from
     * above, so highlighting restarts a few lines up; the window is small for typing and grows
     * only for pathological nesting.
     */
    private void repaintLines(int offset, int inserted) {
      DefaultStyledDocument document = (DefaultStyledDocument) this.pane.getDocument();
      Element root = document.getDefaultRootElement();
      int first = Math.max(0, root.getElementIndex(offset) - 4);
      int last = root.getElementIndex(Math.min(offset + inserted, document.getLength()));
      StyledHighlighter highlighter = StyledHighlighter.styles(this.palette);
      StyledHighlighter.State state = new StyledHighlighter.State();
      try {
        // Rebuild the carry state forward from the document start to the window; the scan is
        // character-cheap next to styling, and keeps multi-line constructs correct.
        for (int line = 0; line < first; ++line) {
          Element element = root.getElement(line);
          int start = element.getStartOffset();
          int end = Math.min(element.getEndOffset(), document.getLength());
          if (end > start) {
            highlighter.scanLine(document.getText(start, end - start), this.format, state);
          }
        }
        for (int line = first; line <= Math.min(last, root.getElementCount() - 1); ++line) {
          Element element = root.getElement(line);
          int start = element.getStartOffset();
          int end = Math.min(element.getEndOffset(), document.getLength());
          if (end > start) {
            highlighter.paintLine(document, start, document.getText(start, end - start),
                this.format, state);
          }
        }
      } catch (RuntimeException | BadLocationException e) {
        // Never break typing for highlighting; the next pause repaints.
      }
    }
  }
}

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
    String content = text == null ? "" : text;
    DefaultStyledDocument document = new DefaultStyledDocument();
    try {
      for (int offset = 0; offset < content.length(); offset += SLICE_CHARS) {
        if (!stillWanted.getAsBoolean()) {
          return null;
        }
        document.insertString(document.getLength(),
            content.substring(offset, Math.min(content.length(), offset + SLICE_CHARS)), null);
      }
    } catch (RuntimeException | BadLocationException e) {
      return null;
    }
    if (!stillWanted.getAsBoolean()) {
      return null;
    }
    try {
      StyledHighlighter highlighter = StyledHighlighter.styles(palette);
      StyledHighlighter.CarryStates carry = new StyledHighlighter.CarryStates();
      if (!highlighter.highlight(document, format, fromLine, stillWanted, carry)) {
        return null;
      }
      document.putProperty(CARRY_STATES_KEY, carry);
    } catch (RuntimeException | BadLocationException e) {
      return null;
    }
    return stillWanted.getAsBoolean() ? document : null;
  }

  /** Document property holding the {@link StyledHighlighter.CarryStates} of a loaded document. */
  static final String CARRY_STATES_KEY = "PrettyView.carryStates";

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

  /** The carry states stored on a document, created on demand. */
  static StyledHighlighter.CarryStates carryOf(DefaultStyledDocument document) {
    Object existing = document.getProperty(CARRY_STATES_KEY);
    if (existing instanceof StyledHighlighter.CarryStates) {
      return (StyledHighlighter.CarryStates) existing;
    }
    StyledHighlighter.CarryStates fresh = new StyledHighlighter.CarryStates();
    document.putProperty(CARRY_STATES_KEY, fresh);
    return fresh;
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
     * Repaints the edited lines, resuming carry state from the closest known line instead of
     * scanning the whole document, so typing stays instant no matter the payload size.
     * Attribute changes are kept out of the undo history: they are not user edits.
     */
    private void repaintLines(int offset, int inserted) {
      DefaultStyledDocument document = (DefaultStyledDocument) this.pane.getDocument();
      StyledHighlighter highlighter = StyledHighlighter.styles(this.palette);
      StyledHighlighter.CarryStates carry = StyledTextLoader.carryOf(document);
      Element root = document.getDefaultRootElement();
      int first = root.getElementIndex(offset);
      int last = root.getElementIndex(Math.min(offset + inserted, document.getLength()));
      carry.dropAfter(first);
      int[] from = new int[1];
      StyledHighlighter.State state = carry.resumeFrom(first, from);
      try {
        for (int line = from[0]; line < first; ++line) {
          Element element = root.getElement(line);
          int start = element.getStartOffset();
          int end = Math.min(element.getEndOffset(), document.getLength());
          if (end > start) {
            highlighter.scanLine(document.getText(start, end - start), this.format, state);
          }
        }
        document.removeUndoableEditListener(this.pane.undoHistory());
        try {
          for (int line = first; line <= last && line < root.getElementCount(); ++line) {
            Element element = root.getElement(line);
            int start = element.getStartOffset();
            int end = Math.min(element.getEndOffset(), document.getLength());
            if (end <= start) {
              continue;
            }
            highlighter.paintLine(document, start, document.getText(start, end - start),
                this.format, state);
            carry.set(line, state);
          }
        } finally {
          document.addUndoableEditListener(this.pane.undoHistory());
        }
      } catch (RuntimeException | BadLocationException e) {
        // Never break typing for highlighting; the next pause repaints.
      }
    }
  }
}

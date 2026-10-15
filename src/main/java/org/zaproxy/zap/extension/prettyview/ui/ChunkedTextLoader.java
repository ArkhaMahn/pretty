package org.zaproxy.zap.extension.prettyview.ui;

import java.util.function.BooleanSupplier;
import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

final class ChunkedTextLoader {
  /**
   * How much text goes into the detached document per step. Slices keep abandonment responsive -
   * the build stops at the next boundary when the payload is superseded - while staying large
   * enough that slicing itself costs nothing measurable.
   */
  private static final int SLICE_CHARS = 262144;

  private ChunkedTextLoader() {
  }

  /**
   * Builds the document for {@code text} without touching the live editor, so this can run on a
   * worker thread. Returns {@code null} when {@code stillWanted} flips mid-build, in which case
   * the caller drops the result.
   *
   * <p>Why detached: inserting into the <em>attached</em> document re-lays-out the whole view on
   * every chunk - 88 twelve-kilobyte chunks of a 1 MB payload cost ~10 s on the EDT against ~0.5 s
   * for the same characters in one document, because each chunk pays a full re-layout of everything
   * inserted so far. Building detached moves all of that off the EDT; attaching is one layout pass
   * (~35 ms for the same payload) and the EDT never blocks on content size.
   *
   * <p>The document is thread-confined until it is attached: no listeners are registered before
   * then, so no EDT state is touched from here. Token makers are created per line during the
   * inserts and stay with the document.
   */
  static RSyntaxDocument buildDocument(String text, String style, BooleanSupplier stillWanted) {
    String content = text == null ? "" : text;
    RSyntaxDocument document = new RSyntaxDocument(
        new BoundedTokenMakerFactory(TokenMakerFactory.getDefaultInstance()), style);
    try {
      for (int offset = 0; offset < content.length(); offset += SLICE_CHARS) {
        if (!stillWanted.getAsBoolean()) {
          return null;
        }
        document.insertString(document.getLength(),
            content.substring(offset, Math.min(content.length(), offset + SLICE_CHARS)), null);
      }
    }
    catch (RuntimeException | BadLocationException e) {
      return null;
    }
    return stillWanted.getAsBoolean() ? document : null;
  }

  /**
   * Swaps the built document into the editor. Must run on the EDT. Replacing the document costs a
   * single layout pass, and because the document arrives fully tokenized there is nothing else to
   * compute. A fresh document also means the load itself never lands in undo history.
   */
  static void installDocument(JTextArea area, RSyntaxDocument document, Runnable keepAtTop, Runnable onComplete) {
    area.setDocument(document);
    if (area instanceof RSyntaxTextArea) {
      ((RSyntaxTextArea)area).setCaretPosition(0);
    }
    keepAtTop.run();
    onComplete.run();
  }
}
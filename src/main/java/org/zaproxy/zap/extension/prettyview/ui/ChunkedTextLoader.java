package org.zaproxy.zap.extension.prettyview.ui;

import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Caret;
import javax.swing.text.DefaultCaret;
import javax.swing.JTextArea;
import javax.swing.text.Document;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

final class ChunkedTextLoader {
  private static final int CHUNK_CHARS = 12000;
  private static final int MIN_CHUNKED_CHARS = 32000;

  private ChunkedTextLoader() {
  }

  /**
   * Replaces the document's contents with {@code text}. {@code keepAtTop} runs as each chunk lands so the
   * caller can hold the viewport at the start of a payload that is still arriving.
   */
  static void load(JTextArea area, String text, String style, BooleanSupplier stillWanted, Runnable keepAtTop, Runnable onComplete) {
    String content = text == null ? "" : text;
    Document document = area.getDocument();
    try {
      document.remove(0, document.getLength());
    }
    catch (BadLocationException badLocationException) {
      // nothing to remove, so there is nothing that can go wrong here either
    }
    if (area instanceof RSyntaxTextArea) {
      ((RSyntaxTextArea)area).setSyntaxEditingStyle(style);
    }
    // Chunks are appended at the end of the document. Under the caret's default update policy it follows
    // every insertion, which drags the viewport down to the bottom of the payload as it streams in.
    // Freezing the policy for the whole load keeps the caret - and so the view - where the caller left it.
    //
    // Wrapping is left alone for the duration. It used to be switched off while the body streamed in and
    // back on at the end, to save re-laying out the line each chunk lands on, but that trade is no longer
    // worth it: the text is already broken into display lines no longer than DisplayLineSplitter's
    // MAX_CHUNK_CHARS, so a landing chunk only re-wraps the short line it ends in, and turning wrapping
    // back on at the end re-flowed the whole document in one visible jump exactly as loading finished.
    // Keeping it on means the payload looks the same the first time it is drawn as it does when done.
    Runnable finish = onComplete;
    Caret caret = area.getCaret();
    if (caret instanceof DefaultCaret) {
      DefaultCaret freezable = (DefaultCaret)caret;
      int restore = freezable.getUpdatePolicy();
      freezable.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
      Runnable inner = finish;
      finish = () -> {
        freezable.setUpdatePolicy(restore);
        inner.run();
      };
    }
    keepAtTop.run();
    if (content.length() < MIN_CHUNKED_CHARS) {
      area.setText(content);
      keepAtTop.run();
      SwingUtilities.invokeLater(finish);
      return;
    }
    ChunkedTextLoader.install(area, document, content, 0, stillWanted, keepAtTop, finish);
  }

  private static int sliceEnd(String text, int offset) {
    int minimum = Math.min(offset + CHUNK_CHARS, text.length());
    int newline = text.indexOf(10, minimum);
    if (newline < 0) {
      return minimum;
    }
    return newline + 1;
  }

  private static void install(JTextArea area, Document document, String text, int offset, BooleanSupplier stillWanted, Runnable keepAtTop, Runnable onComplete) {
    boolean abandoned = !stillWanted.getAsBoolean();
    if (!abandoned) {
      int end = ChunkedTextLoader.sliceEnd(text, offset);
      try {
        document.insertString(offset, text.substring(offset, end), null);
        keepAtTop.run();
      }
      catch (RuntimeException | BadLocationException e) {
        abandoned = true;
      }
    }
    boolean done = abandoned || ChunkedTextLoader.documentLength(document) >= text.length();
    if (done) {
      onComplete.run();
      return;
    }
    SwingUtilities.invokeLater(() -> ChunkedTextLoader.install(area, document, text, ChunkedTextLoader.sliceEnd(text, offset), stillWanted, keepAtTop, onComplete));
  }

  private static int documentLength(Document document) {
    try {
      return document.getLength();
    }
    catch (RuntimeException e) {
      return Integer.MAX_VALUE;
    }
  }
}
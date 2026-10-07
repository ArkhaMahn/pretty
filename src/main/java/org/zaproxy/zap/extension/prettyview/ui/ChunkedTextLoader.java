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
    // Wrapping is also switched off for the duration and put back in finish(). With wrapping on, every
    // chunk re-wraps the whole line it lands on, which is what makes streaming slow: a 652 KB body that is
    // one line measures about 3.5 s that way against 220 ms unwrapped, and the plain body exists only to
    // avoid the same cost being 50 s in the syntax editor. One wrap pass at the end instead of one per
    // chunk. The trade is that while the payload is streaming it is briefly not wrapped.
    boolean wrapping = area.getLineWrap();
    boolean wordWrapping = area.getWrapStyleWord();
    area.setLineWrap(false);
    Runnable finish = () -> {
      area.setLineWrap(wrapping);
      area.setWrapStyleWord(wordWrapping);
      onComplete.run();
    };
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
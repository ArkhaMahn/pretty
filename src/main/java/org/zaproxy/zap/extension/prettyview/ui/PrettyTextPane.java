package org.zaproxy.zap.extension.prettyview.ui;

import javax.swing.JTextPane;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BoxView;
import javax.swing.text.ComponentView;
import javax.swing.text.Element;
import javax.swing.text.IconView;
import javax.swing.text.LabelView;
import javax.swing.text.ParagraphView;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.undo.UndoManager;

/**
 * The editor the Pretty view renders into: a plain {@link JTextPane} with its own wrapping.
 *
 * <p>Highlighting lives in character attributes painted by the document (see {@link
 * StyledHighlighter}), so the component itself stays dumb: it shows text, wraps it, and edits
 * it. Rows are measured per line rather than per token, which is what keeps scrolling cheap
 * however long a line runs.
 */
final class PrettyTextPane extends JTextPane {
  private static final long serialVersionUID = 1L;

  /** Undo history for editable hosts; read-only views never fill it. */
  private final UndoManager undoManager = new UndoManager();
  /** Whether lines wrap at the view width. Mirrors the View menu toggle. */
  private boolean wrapped = true;

  PrettyTextPane() {
    super();
    this.getActionMap().put("pretty-undo", new javax.swing.AbstractAction() {
      private static final long serialVersionUID = 1L;

      @Override
      public void actionPerformed(java.awt.event.ActionEvent event) {
        if (PrettyTextPane.this.undoManager.canUndo()) {
          PrettyTextPane.this.undoManager.undo();
        }
      }
    });
    this.getActionMap().put("pretty-redo", new javax.swing.AbstractAction() {
      private static final long serialVersionUID = 1L;

      @Override
      public void actionPerformed(java.awt.event.ActionEvent event) {
        if (PrettyTextPane.this.undoManager.canRedo()) {
          PrettyTextPane.this.undoManager.redo();
        }
      }
    });
    this.getInputMap(WHEN_FOCUSED).put(
        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK),
        "pretty-undo");
    this.getInputMap(WHEN_FOCUSED).put(
        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_DOWN_MASK),
        "pretty-redo");
    // Tabs emulate two spaces, like the indenter draws, instead of a tab stop.
    this.getActionMap().put("pretty-tab", new javax.swing.AbstractAction() {
      private static final long serialVersionUID = 1L;

      @Override
      public void actionPerformed(java.awt.event.ActionEvent event) {
        if (PrettyTextPane.this.isEditable()) {
          PrettyTextPane.this.replaceSelection("  ");
        }
      }
    });
    this.getInputMap(WHEN_FOCUSED).put(
        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_TAB, 0), "pretty-tab");
    this.getActionMap().put("pretty-untab", new javax.swing.AbstractAction() {
      private static final long serialVersionUID = 1L;

      @Override
      public void actionPerformed(java.awt.event.ActionEvent event) {
        PrettyTextPane.this.unindentCaretLine();
      }
    });
    this.getInputMap(WHEN_FOCUSED).put(
        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_TAB,
            java.awt.event.InputEvent.SHIFT_DOWN_MASK),
        "pretty-untab");
    // Tab indents instead of leaving the editor, like every message editor.
    this.setFocusTraversalKeysEnabled(false);
  }

  /** Removes one indent level from the caret line, if it has one. */
  private void unindentCaretLine() {
    if (!this.isEditable()) {
      return;
    }
    try {
      int caret = this.getCaretPosition();
      javax.swing.text.Element line =
          this.getDocument().getDefaultRootElement().getElement(
              this.getDocument().getDefaultRootElement().getElementIndex(caret));
      int start = line.getStartOffset();
      int removable = 0;
      String head = this.getDocument().getText(start, Math.min(2, line.getEndOffset() - start));
      while (removable < head.length() && head.charAt(removable) == ' ') {
        ++removable;
      }
      if (removable > 0) {
        this.getDocument().remove(start, removable);
      }
    } catch (javax.swing.text.BadLocationException e) {
      // Caret raced an edit; nothing to unindent.
    }
  }

  /**
   * No hover tooltips, ever: no link or occurrence tips, nothing following the mouse over the
   * message.
   */
  @Override
  public String getToolTipText(java.awt.event.MouseEvent event) {
    return null;
  }

  /** Whether lines wrap at the view width. */
  boolean isWrapped() {
    return this.wrapped;
  }

  /**
   * Forces the viewport to give the view its own width while wrapped, so rows always break at
   * the view width. The stock rule tracks only while the preferred width fits, which flips back
   * and forth as chunked inserts land and settles path-dependently - sometimes wrapped at the
   * viewport, sometimes laid out at a transient preferred width with cut-off rows.
   */
  @Override
  public boolean getScrollableTracksViewportWidth() {
    return this.wrapped;
  }

  @Override
  public boolean getScrollableTracksViewportHeight() {
    return false;
  }

  /**
   * Wraps or unwraps at the view width. Unwrapping swaps in a non-wrapping kit; the document is
   * lifted over the swap so text, caret and undo history survive it.
   */
  void setWrapped(boolean wrap) {
    boolean kitWrapped = !(this.getEditorKit() instanceof NoWrapKit);
    if (wrap == this.wrapped && wrap == kitWrapped) {
      return;
    }
    javax.swing.text.Document document = this.getDocument();
    int caret = Math.min(this.getCaretPosition(), document.getLength());
    this.wrapped = wrap;
    this.setEditorKit(wrap ? new StyledEditorKit() : new NoWrapKit());
    this.setDocument(document);
    if (caret <= this.getDocument().getLength()) {
      this.setCaretPosition(caret);
    }
  }

  @Override
  public void setDocument(javax.swing.text.Document document) {
    // The undo history follows the document; the null checks cover construction, when this
    // runs before the field initializers.
    if (this.undoManager != null) {
      javax.swing.text.Document old = this.getDocument();
      if (old != null) {
        old.removeUndoableEditListener(this.undoManager);
      }
    }
    super.setDocument(document);
    if (this.undoManager != null && document != null) {
      document.addUndoableEditListener(this.undoManager);
    }
  }

  /** Forgets loaded text's undo history, so only user edits are undoable. */
  void discardAllEdits() {
    this.undoManager.discardAllEdits();
  }

  /** The undo history, so highlighting repaints can keep their attributes out of it. */
  javax.swing.undo.UndoManager undoHistory() {
    return this.undoManager;
  }

  /**
   * A styled kit whose paragraphs never wrap: each line lays out on one row however long it
   * runs, and the horizontal scrollbar covers the rest.
   */
  static final class NoWrapKit extends StyledEditorKit {
    private static final long serialVersionUID = 1L;

    private final ViewFactory factory = new ViewFactory() {
      @Override
      public View create(Element element) {
        String kind = element.getName();
        if (kind == null) {
          return new LabelView(element);
        }
        switch (kind) {
          case AbstractDocument.ContentElementName:
            return new LabelView(element);
          case AbstractDocument.ParagraphElementName:
            return new NoWrapParagraphView(element);
          case AbstractDocument.SectionElementName:
            return new BoxView(element, View.Y_AXIS);
          case StyleConstants.ComponentElementName:
            return new ComponentView(element);
          case StyleConstants.IconElementName:
            return new IconView(element);
          default:
            return new LabelView(element);
        }
      }
    };

    @Override
    public ViewFactory getViewFactory() {
      return this.factory;
    }
  }

  /** A paragraph that lays its content on one unwrapped row. */
  static final class NoWrapParagraphView extends ParagraphView {
    NoWrapParagraphView(Element element) {
      super(element);
    }

    @Override
    public void layout(int width, int height) {
      super.layout(Short.MAX_VALUE, height);
    }

    @Override
    public float getMinimumSpan(int axis) {
      if (axis == View.X_AXIS) {
        return super.getPreferredSpan(axis);
      }
      return super.getMinimumSpan(axis);
    }
  }
}

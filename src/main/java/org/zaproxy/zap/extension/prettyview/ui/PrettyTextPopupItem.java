package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.Component;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.io.IOException;
import javax.swing.Action;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.parosproxy.paros.extension.ExtensionPopupMenuItem;
import org.zaproxy.zap.view.messagecontainer.MessageContainer;
import org.zaproxy.zap.view.popup.ExtensionPopupMenuComponent;

/**
 * The Pretty view's text actions, heading ZAP's message popup instead of living in a private menu.
 *
 * <p>One flat right-click menu holds everything: these entries (undo through select-all) come first
 * and ZAP's message actions (Attack and friends) follow. Each entry only shows while the click
 * landed in a Pretty editor - everywhere else the enable checks below fail and ZAP hides the
 * entries - so registering them globally changes no other popup. Nothing here holds a view: the
 * editor and its panel are captured fresh on every show from the click target, so unloading the
 * add-on (or closing a window) simply stops matching.
 */
public final class PrettyTextPopupItem extends ExtensionPopupMenuItem {
  private static final long serialVersionUID = 1L;

  /** The text actions, in menu order. */
  public enum Kind {
    UNDO("Undo"),
    REDO("Redo"),
    CUT("Cut"),
    COPY("Copy"),
    PASTE("Paste"),
    DELETE("Delete"),
    FIND("Find..."),
    FIND_NEXT("Find Next"),
    FIND_PREVIOUS("Find Previous"),
    SELECT_ALL("Select All");

    final String label;

    Kind(String label) {
      this.label = label;
    }
  }

  /**
   * Weight of the first entry. ZAP lays its menu out in <em>descending</em> weight order - each
   * entry goes before the first one lighter than itself - and everything ships at 1000, so the
   * block needs heavier weights to head the menu. Weights descend within the block for the same
   * reason: each later entry must be lighter than the one before it, or it would jump ahead of it.
   */
  private static final int FIRST_WEIGHT = 10000;

  private final Kind kind;
  private JTextComponent invoker;
  private PrettyViewPanel panel;

  public PrettyTextPopupItem(Kind kind) {
    super(kind.label);
    this.kind = kind;
    this.setWeight(FIRST_WEIGHT - kind.ordinal());
    this.addActionListener(this::onClick);
  }

  @Override
  public void doClick() {
    // ZAP's popup framework may call doClick() directly instead of firing action listeners.
    // Ensure our handler runs regardless of the invocation path.
    this.onClick(null);
  }

  @Override
  public boolean precedeWithSeparator() {
    return this.kind == Kind.CUT || this.kind == Kind.FIND || this.kind == Kind.SELECT_ALL;
  }

  @Override
  public boolean succeedWithSeparator() {
    // Divides the text block from the message actions that follow it.
    return this.kind == Kind.SELECT_ALL;
  }

  @Override
  public boolean isSafe() {
    // Local text editing and in-editor find stay available in safe mode.
    return true;
  }

  @Override
  public boolean isEnableForComponent(Component component) {
    boolean ours = this.capture(component);
    if (ours) {
      this.refreshEnabled();
    }
    return ours;
  }

  @Override
  public boolean isEnableForMessageContainer(MessageContainer<?> container) {
    boolean ours = container != null && this.capture(container.getComponent());
    if (ours) {
      this.refreshEnabled();
    }
    return ours;
  }

  @Override
  public void dismissed(ExtensionPopupMenuComponent unused) {
    this.invoker = null;
    this.panel = null;
  }

  /**
   * Remembers the click target when it is a Pretty editor. Returns false for everything else, in
   * which case ZAP hides this entry.
   */
  private boolean capture(Component component) {
    this.invoker = null;
    this.panel = null;
    if (!(component instanceof JTextComponent)) {
      return false;
    }
    PrettyViewPanel panel =
        (PrettyViewPanel) SwingUtilities.getAncestorOfClass(PrettyViewPanel.class, component);
    if (panel == null) {
      return false;
    }
    this.invoker = (JTextComponent) component;
    this.panel = panel;
    return true;
  }

  /** Greys the entry the way the old menu did: read-only areas offer no Cut, Paste or Delete. */
  private void refreshEnabled() {
    JTextComponent area = this.invoker;
    if (area == null) {
      this.setEnabled(false);
      return;
    }
    switch (this.kind) {
      case UNDO:
        this.setEnabled(PrettyTextPopupItem.action(area, "RTA.UndoAction") != null
            && PrettyTextPopupItem.canUndo(area));
        break;
      case REDO:
        this.setEnabled(PrettyTextPopupItem.action(area, "RTA.RedoAction") != null
            && PrettyTextPopupItem.canRedo(area));
        break;
      case CUT:
      case PASTE:
        this.setEnabled(area.isEditable());
        break;
      case DELETE:
        this.setEnabled(area.isEditable()
            && area.getSelectionStart() != area.getSelectionEnd());
        break;
      case COPY:
      case SELECT_ALL:
      case FIND:
      case FIND_NEXT:
      case FIND_PREVIOUS:
      default:
        this.setEnabled(true);
        break;
    }
  }

  private void onClick(ActionEvent event) {
    JTextComponent area = this.invoker;
    PrettyViewPanel panel = this.panel;
    if (area == null || panel == null) {
      return;
    }
    switch (this.kind) {
      case UNDO:
        PrettyTextPopupItem.run(area, "RTA.UndoAction");
        break;
      case REDO:
        PrettyTextPopupItem.run(area, "RTA.RedoAction");
        break;
      case CUT:
        // Done directly on the invoker instead of firing a clipboard action: looked-up actions
        // resolve their target through focus state, which has already moved to the menu itself.
        PrettyTextPopupItem.cutSelection(area);
        break;
      case COPY:
        // Deliberately looks "copy" up at click time: it is the split-aware action once a payload
        // is shown, so Copy hands back the message's characters rather than the editor's inserted
        // line breaks.
        PrettyTextPopupItem.run(area, "copy", "copy-to-clipboard");
        break;
      case PASTE:
        PrettyTextPopupItem.pasteClipboard(area);
        break;
      case DELETE:
        if (area.isEditable() && area.getSelectionStart() != area.getSelectionEnd()) {
          area.replaceSelection("");
        }
        break;
      case FIND:
        panel.openSearch();
        break;
      case FIND_NEXT:
        panel.findNext();
        break;
      case FIND_PREVIOUS:
        panel.findPrevious();
        break;
      case SELECT_ALL:
      default:
        area.selectAll();
        break;
    }
  }

  /** Copies the selection to the clipboard; a no-op without a selection. */
  public static void copySelection(JTextComponent area) {
    String selected = area.getSelectedText();
    if (selected == null || selected.isEmpty()) {
      return;
    }
    try {
      Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
          new StringSelection(selected), null);
    } catch (HeadlessException | IllegalStateException | SecurityException e) {
      // No clipboard available.
    }
  }

  /** Cuts the selection to the clipboard; a no-op without an editable selection. */
  public static void cutSelection(JTextComponent area) {
    if (!area.isEditable()) {
      return;
    }
    String selected = area.getSelectedText();
    if (selected == null || selected.isEmpty()) {
      return;
    }
    try {
      Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
          new StringSelection(selected), null);
      area.replaceSelection("");
    } catch (HeadlessException | IllegalStateException | SecurityException e) {
      // No clipboard available; the text is left untouched.
    }
  }

  /** Pastes the clipboard string at the caret; a no-op when read-only or stringless. */
  public static void pasteClipboard(JTextComponent area) {
    if (!area.isEditable()) {
      return;
    }
    try {
      Transferable contents =
          Toolkit.getDefaultToolkit().getSystemClipboard().getContents(area);
      if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        String text = (String) contents.getTransferData(DataFlavor.stringFlavor);
        area.replaceSelection(text == null ? "" : text);
      }
    } catch (HeadlessException | IllegalStateException | SecurityException | UnsupportedFlavorException | IOException e) {
      // No pasteable string available; the text is left untouched.
    }
  }

  private static Action action(JTextComponent area, String key) {
    return area.getActionMap().get(key);
  }

  private static boolean canUndo(JTextComponent area) {
    return area instanceof RSyntaxTextArea && ((RSyntaxTextArea) area).canUndo();
  }

  private static boolean canRedo(JTextComponent area) {
    return area instanceof RSyntaxTextArea && ((RSyntaxTextArea) area).canRedo();
  }

  private static void run(JTextComponent area, String... keys) {
    for (String key : keys) {
      Action action = area.getActionMap().get(key);
      if (action != null) {
        action.actionPerformed(new ActionEvent(area, ActionEvent.ACTION_PERFORMED, key));
        return;
      }
    }
  }
}

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
import javax.swing.Action;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.extension.ExtensionPopupMenuItem;
import org.zaproxy.zap.view.messagecontainer.MessageContainer;
import org.zaproxy.zap.view.popup.ExtensionPopupMenuComponent;

/**
 * Pretty's text actions as global message-popup entries, ordered exactly like core's own text
 * entries. Each entry only shows while the click landed in a Pretty editor - everywhere else the
 * enable checks below fail and ZAP hides the entries - so registering them globally changes no
 * other popup. Nothing here holds a view: the editor and its panel are captured fresh on every
 * show from the click target. All actions work directly on the captured editor, never through
 * focus state.
 */
public final class PrettyTextPopupItem extends ExtensionPopupMenuItem {
  private static final long serialVersionUID = 1L;
  private static final Logger LOGGER = LogManager.getLogger(PrettyTextPopupItem.class);

  /** The text actions. Weights match ZAP's own text entries so core order is kept. */
  public enum Kind {
    UNDO("Undo", 19060),
    REDO("Redo", 19040),
    CUT("Cut", 17080),
    COPY("Copy", 17060),
    PASTE("Paste", 17040),
    DELETE("Delete", 17020),
    SELECT_ALL("Select All", 15050);

    final String label;
    final int weight;

    Kind(String label, int weight) {
      this.label = label;
      this.weight = weight;
    }
  }

  private final Kind kind;
  private JTextComponent invoker;
  private PrettyViewPanel panel;

  public PrettyTextPopupItem(Kind kind) {
    super(kind.label);
    this.kind = kind;
    this.setWeight(kind.weight);
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
    return false;
  }

  @Override
  public boolean succeedWithSeparator() {
    return false;
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
    LOGGER.info("PrettyView menu item {} enable={} (diag-menu-1)", this.kind, ours);
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

  /** Greys entries that cannot act: read-only areas offer no Cut, Paste or Delete. */
  private void refreshEnabled() {
    JTextComponent area = this.invoker;
    if (area == null) {
      this.setEnabled(false);
      return;
    }
    switch (this.kind) {
      case UNDO:
        this.setText(PrettyTextPopupItem.canUndo(area) ? "Undo" : "Can't Undo");
        this.setEnabled(PrettyTextPopupItem.action(area, "RTA.UndoAction") != null
            && PrettyTextPopupItem.canUndo(area));
        break;
      case REDO:
        this.setText(PrettyTextPopupItem.canRedo(area) ? "Redo" : "Can't Redo");
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
      default:
        this.setEnabled(true);
        break;
    }
  }

  private void onClick(ActionEvent event) {
    LOGGER.info("PrettyView menu item {} clicked (diag-menu-1)", this.kind);
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
        // Split-aware through the owning panel: Copy hands back the message's characters
        // rather than the editor's inserted line breaks.
        panel.copySelection();
        break;
      case PASTE:
        PrettyTextPopupItem.pasteClipboard(area);
        break;
      case DELETE:
        if (area.isEditable() && area.getSelectionStart() != area.getSelectionEnd()) {
          area.replaceSelection("");
        }
        break;
      case SELECT_ALL:
      default:
        area.selectAll();
        break;
    }
  }

  /** Cuts the selection to the clipboard; a no-op without an editable selection. */
  private static void cutSelection(JTextComponent area) {
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
  private static void pasteClipboard(JTextComponent area) {
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

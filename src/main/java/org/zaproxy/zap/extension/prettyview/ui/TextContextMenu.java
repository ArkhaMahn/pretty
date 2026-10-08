package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.Action;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;

/**
 * The right-click menu for a text area, shared by the Pretty view and ZAP's own request and response
 * panels so both offer the same entries.
 *
 * <p>The items are rebuilt every time the menu opens, and each one is taken from the target's own
 * action map. The same menu therefore works on the Pretty editor (RSyntaxTextArea) and on ZAP's plain
 * text areas, whose action names differ, and it always offers whatever is current: the copy action on
 * the Pretty editor is swapped for the split-aware one once a payload is shown, and that swap is picked
 * up automatically when the menu opens.
 *
 * <p>Find is wired to a caller-supplied action, so the Pretty view can open its inline find bar while
 * the core panels can hand off to ZAP's Search tab. Undo, Redo, Cut and Paste are greyed out on a read
 * only area, which is the state ZAP's main request and response views are in.
 */
public final class TextContextMenu
extends JPopupMenu {
  private static final long serialVersionUID = 1L;
  private final Runnable find;
  private final Runnable findNext;
  private final Runnable findPrevious;

  public TextContextMenu(Runnable find, Runnable findNext, Runnable findPrevious) {
    this.find = find;
    this.findNext = findNext;
    this.findPrevious = findPrevious;
  }

  @Override
  public void show(Component invoker, int x, int y) {
    this.populate(invoker instanceof JTextComponent ? (JTextComponent) invoker : null);
    super.show(invoker, x, y);
  }

  private void populate(JTextComponent area) {
    this.removeAll();
    boolean editable = area != null && area.isEditable();
    this.add(this.actionItem(area, "Undo", editable, "RTA.UndoAction", "Undo"));
    this.add(this.actionItem(area, "Redo", editable, "RTA.RedoAction", "Redo"));
    this.addSeparator();
    this.add(this.actionItem(area, "Cut", editable, "cut-to-clipboard", "cut"));
    this.add(this.actionItem(area, "Copy", true, "copy", "copy-to-clipboard"));
    this.add(this.actionItem(area, "Paste", editable, "paste-from-clipboard", "paste"));
    this.add(this.deleteItem(area));
    this.addSeparator();
    this.add(this.runItem("Find...", KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), this.find));
    if (this.findNext != null) {
      this.add(this.runItem("Find Next", KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), this.findNext));
    }
    if (this.findPrevious != null) {
      this.add(this.runItem("Find Previous", KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), this.findPrevious));
    }
    this.addSeparator();
    this.add(this.actionItem(area, "Select All", true, "select-all"));
  }

  private JMenuItem actionItem(JTextComponent area, String label, boolean enabled, String... keys) {
    Action action = area == null ? null : TextContextMenu.action(area, keys);
    JMenuItem item = action == null ? new JMenuItem(label) : new JMenuItem(action);
    item.setText(label);
    item.setEnabled(enabled && action != null);
    return item;
  }

  private JMenuItem deleteItem(JTextComponent area) {
    JMenuItem item = new JMenuItem("Delete");
    item.addActionListener(event -> {
      if (area != null) {
        area.replaceSelection("");
      }
    });
    item.setEnabled(area != null && area.isEditable()
        && area.getSelectionStart() != area.getSelectionEnd());
    return item;
  }

  private JMenuItem runItem(String text, KeyStroke accelerator, Runnable action) {
    JMenuItem item = new JMenuItem(text);
    if (accelerator != null) {
      item.setAccelerator(accelerator);
    }
    item.addActionListener(event -> action.run());
    return item;
  }

  private static Action action(JTextComponent area, String... keys) {
    for (String key : keys) {
      Action candidate = area.getActionMap().get(key);
      if (candidate != null) {
        return candidate;
      }
    }
    return null;
  }
}

package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.parosproxy.paros.extension.ExtensionPopupMenuItem;
import org.zaproxy.zap.view.messagecontainer.MessageContainer;
import org.zaproxy.zap.view.popup.ExtensionPopupMenuComponent;

/**
 * Pretty's View menu: a "Line wrapping" entry under a trailing "View" submenu, on by default.
 * The entry only shows while the click landed in a Pretty editor - everywhere else the enable
 * checks fail and ZAP hides it - and the check mark follows the editor it was opened on. Nothing
 * here holds a view: the editor is captured fresh on every show from the click target.
 */
public final class PrettyViewPopupItem extends ExtensionPopupMenuItem {
  private static final long serialVersionUID = 1L;
  private static final Logger LOGGER = LogManager.getLogger(PrettyViewPopupItem.class);
  /** Last so the View menu reads as the final section, below every core and text entry. */
  static final int PARENT_WEIGHT = 100;

  private PrettyTextPane invoker;

  public PrettyViewPopupItem() {
    super("Line wrapping");
    this.setParentWeight(PARENT_WEIGHT);
    this.setWeight(PARENT_WEIGHT);
    // Capturing on show replaces this before anything paints; the default keeps the slot
    // aligned even if the item is ever rendered without a show first.
    this.setIcon(new CheckIcon(false));
    this.addActionListener(this::onClick);
  }

  @Override
  public String getParentMenuName() {
    return "View";
  }

  @Override
  public boolean isSubMenu() {
    return true;
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
    // A display-only wrap toggle touches no message bytes.
    return true;
  }

  @Override
  public boolean isEnableForComponent(Component component) {
    boolean ours = this.capture(component);
    if (ours) {
      this.refreshCheck();
    }
    return ours;
  }

  @Override
  public boolean isEnableForMessageContainer(MessageContainer<?> container) {
    boolean ours = container != null && this.capture(container.getComponent());
    if (ours) {
      this.refreshCheck();
    }
    return ours;
  }

  @Override
  public void dismissed(ExtensionPopupMenuComponent unused) {
    // Intentionally keeps the last capture: ZAP hides the menu (firing this) just before
    // delivering the click, so clearing here would wipe the target out from under onClick.
    // capture() refreshes the field on every show, so a click always sees the showing menu.
  }

  /**
   * Remembers the click target when it is a Pretty editor. Returns false for everything else, in
   * which case ZAP hides this entry.
   */
  private boolean capture(Component component) {
    this.invoker = null;
    if (!(component instanceof PrettyTextPane)) {
      return false;
    }
    if (SwingUtilities.getAncestorOfClass(PrettyViewPanel.class, component) == null) {
      return false;
    }
    this.invoker = (PrettyTextPane) component;
    return true;
  }

  /** Draws the check mark in the menu's own foreground, so it reads in both themes. */
  private void refreshCheck() {
    PrettyTextPane pane = this.invoker;
    this.setIcon(new CheckIcon(pane != null && pane.isWrapped()));
  }

  private void onClick(ActionEvent event) {
    PrettyTextPane pane = this.invoker;
    if (pane == null) {
      LOGGER.warn("PrettyView line-wrapping click with no captured editor");
      return;
    }
    boolean wrap = !pane.isWrapped();
    PrettyLineWrap.setEnabled(wrap);
    PrettyViewPanel panel = null;
    java.awt.Container parent = pane.getParent();
    while (parent != null) {
      if (parent instanceof PrettyViewPanel) {
        panel = (PrettyViewPanel) parent;
        break;
      }
      parent = parent.getParent();
    }
    if (panel != null) {
      panel.setWrapped(wrap);
    } else {
      pane.setWrapped(wrap);
    }
    this.refreshCheck();
  }

  /** A check mark that paints in whatever foreground the menu item carries. */
  private static final class CheckIcon implements Icon {
    private static final int SIZE = 16;
    private final boolean checked;

    CheckIcon(boolean checked) {
      this.checked = checked;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
      if (!this.checked) {
        return;
      }
      Graphics2D g2 = (Graphics2D) g.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(c == null ? Color.BLACK : c.getForeground());
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawLine(x + 3, y + 8, x + 7, y + 12);
        g2.drawLine(x + 7, y + 12, x + 13, y + 4);
      } finally {
        g2.dispose();
      }
    }

    @Override
    public int getIconWidth() {
      return SIZE;
    }

    @Override
    public int getIconHeight() {
      return SIZE;
    }
  }
}

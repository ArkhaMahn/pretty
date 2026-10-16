package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import javax.swing.text.JTextComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.view.MainPopupMenu;
import org.parosproxy.paros.view.View;
import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.extension.httppanel.view.text.HttpPanelTextView;
import org.zaproxy.zap.view.messagecontainer.MessageContainer;
import org.zaproxy.zap.view.messagecontainer.http.DefaultSingleHttpMessageContainer;

public final class PrettyViewPanel
extends JPanel {
  private static final Logger LOGGER = LogManager.getLogger(PrettyViewPanel.class);
  private static final long serialVersionUID = 1L;
  private static final String COPY_KEY = "copy";
  private final PrettyTextPane textPane;
  private final JScrollPane scrollPane;
  private final LineNumberGutter gutter;
  private final PrettyNoticeBar noticeBar;
  private final PrettySearchBar searchBar;
  /** True while a payload is streaming in, which is when the view is held at the top of the document. */
  private boolean loading;
  /** Set once the user scrolls or clicks during a load, after which the view is left entirely alone. */
  private boolean userMovedView;
  /** The model providing message data for ZAP actions. */
  private final PrettyViewModel model;

  public PrettyViewPanel(PrettyTextPane textPane, PrettyViewModel model) {
    super(new BorderLayout());
    this.textPane = textPane;
    this.model = model;
    // No Pretty-owned popup: right-click shows ZAP's own message popup, exactly like the core
    // text views (focus grab, then MainPopupMenu for this message).
    this.textPane.setComponentPopupMenu(null);
    this.textPane.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent event) {
        PrettyViewPanel.this.showCoreMenu(event);
      }

      @Override
      public void mouseReleased(MouseEvent event) {
        PrettyViewPanel.this.showCoreMenu(event);
      }
    });
    this.scrollPane = new JScrollPane(textPane);
    this.scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
    // Wrapped by default, so no horizontal bar until unwrapping asks for one.
    this.scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    this.gutter = new LineNumberGutter(textPane);
    this.scrollPane.setRowHeaderView(this.gutter);
    this.noticeBar = new PrettyNoticeBar();
    this.searchBar = new PrettySearchBar(textPane);
    EditorTheme.apply(textPane, this.scrollPane, EditorTheme.palette());
    JPanel header = new JPanel(new BorderLayout());
    header.add((Component)this.noticeBar, "North");
    header.add((Component)this.searchBar, "South");
    this.add((Component)header, "North");
    this.add((Component)this.scrollPane, "Center");
    JScrollBar vertical = this.scrollPane.getVerticalScrollBar();
    if (vertical != null) {
      vertical.addMouseListener(new MouseAdapter() {
        @Override
        public void mousePressed(MouseEvent event) {
          PrettyViewPanel.this.noteUserMovedView();
        }
      });
      vertical.addMouseMotionListener(new MouseMotionAdapter() {
        @Override
        public void mouseDragged(MouseEvent event) {
          PrettyViewPanel.this.noteUserMovedView();
        }
      });
    }
    // Only genuine input counts as the user taking over. Watching the scrollbar value or the caret
    // position instead would flag the loader's own appends, because Swing scrolls to the caret as the
    // document grows, and the pin would then disable itself on the first chunk.
    this.scrollPane.addMouseWheelListener(event -> this.noteUserMovedView());
    this.watchForClicks(this.scrollPane.getViewport());
    this.watchForKeys(this.textPane);
    this.installScrolling();
    this.installSearchKeys();
  }

  /**
   * Shows ZAP's own message popup on right-click, exactly like the core text views: focus back
   * to the editor first, caret to the click point when nothing is selected, then the message
   * popup for the current message.
   */
  private void showCoreMenu(MouseEvent event) {
    if (!event.isPopupTrigger()) {
      return;
    }
    if (!this.textPane.isFocusOwner()) {
      this.textPane.requestFocusInWindow();
    }
    if (this.textPane.getSelectionStart() == this.textPane.getSelectionEnd()) {
      int position = this.textPane.viewToModel(event.getPoint());
      if (position >= 0) {
        this.textPane.setCaretPosition(position);
      }
    }
    Message message = this.model.getMessage();
    if (!(message instanceof HttpMessage)) {
      LOGGER.info("PrettyView menu: no HttpMessage, menu skipped (diag-menu-1)");
      return;
    }
    try {
      View view = View.getSingleton();
      if (view == null) {
        return;
      }
      view.getPopupMenu().show(
          new DefaultSingleHttpMessageContainer(
              HttpPanelTextView.DEFAULT_MESSAGE_CONTAINER_NAME,
              this.textPane,
              (HttpMessage) message),
          event.getX(),
          event.getY());
    } catch (RuntimeException e) {
      LOGGER.debug("PrettyView right-click menu unavailable", e);
    }
  }

  /**
   * Makes the wheel move the view in small discrete steps rather than whole lines at a time.
   *
   * <p>Swing's default unit increment comes from the view, and on a text area that can be a few
   * pixels, which reads as scrolling that barely moves however hard the wheel is turned - while
   * a whole line per notch jumps. A third of a row steps instead, at the same paint cost per
   * notch either way. No animation: every notch lands in a single paint each.
   */
  private void installScrolling() {
    this.applyScrollUnit();
    this.scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent event) {
        PrettyViewPanel.this.applyScrollBlock();
      }
    });
  }

  private void applyScrollUnit() {
    int unit = Math.max(8, this.textPane.getFontMetrics(this.textPane.getFont()).getHeight() / 3);
    JScrollBar vertical = this.scrollPane.getVerticalScrollBar();
    if (vertical != null) {
      vertical.setUnitIncrement(unit);
    }
    JScrollBar horizontal = this.scrollPane.getHorizontalScrollBar();
    if (horizontal != null) {
      horizontal.setUnitIncrement(unit);
    }
    this.applyScrollBlock();
  }

  private void applyScrollBlock() {
    JScrollBar vertical = this.scrollPane.getVerticalScrollBar();
    if (vertical == null) {
      return;
    }
    int unit = Math.max(8, this.textPane.getFontMetrics(this.textPane.getFont()).getHeight());
    int height = this.scrollPane.getViewport().getHeight();
    vertical.setBlockIncrement(Math.max(unit, height - unit));
  }

  /** Walks to the next find hit; the popup's Find Next entry calls this. */
  boolean findNext() {
    return this.searchBar.findNext();
  }

  /** Walks to the previous find hit; the popup's Find Previous entry calls this. */
  boolean findPrevious() {
    return this.searchBar.findPrevious();
  }

  /** Ctrl+F opens the find bar, F3 walks the matches, and Escape puts the bar away again. */
  private void installSearchKeys() {
    this.bind(this.textPane, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "pretty-find", this::openSearch);
    this.bind(this.textPane, KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "pretty-find-next", this.searchBar::findNext);
    this.bind(this.textPane, KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "pretty-find-previous", this.searchBar::findPrevious);
    this.bind(this.textPane, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "pretty-find-close", () -> {
      if (this.searchBar.isOpen()) {
        this.searchBar.close();
      }
    });
  }

  private void bind(JComponent component, KeyStroke stroke, String name, Runnable action) {
    component.getActionMap().put(name, new AbstractAction() {
      private static final long serialVersionUID = 1L;

      @Override
      public void actionPerformed(ActionEvent event) {
        action.run();
      }
    });
    component.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, name);
  }

  /** Opens the find bar; the popup's Find entry and Ctrl+F call this. */
  void openSearch() {
    this.searchBar.open(this.textPane.getSelectedText());
  }

  private void watchForClicks(Component viewport) {
    viewport.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent event) {
        PrettyViewPanel.this.noteUserMovedView();
      }
    });
  }

  private void watchForKeys(JTextComponent component) {
    component.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent event) {
        PrettyViewPanel.this.noteUserMovedView();
      }
    });
  }

  private void noteUserMovedView() {
    if (this.loading) {
      this.userMovedView = true;
    }
  }

  /**
   * Holds the view at the top of the document while a payload is loaded. A large response arrives in
   * chunks appended at the end of the document, so without this the viewport is dragged down with every
   * chunk and the view ends up at the bottom of the response instead of the start.
   */
  public void beginLoad() {
    PrettyViewPanel.onEdt(() -> {
      this.loading = true;
      this.userMovedView = false;
      this.pinToTop();
    });
  }

  /** Releases the view once the document is complete, leaving it at the top unless the user moved it. */
  public void endLoad() {
    PrettyViewPanel.onEdt(() -> {
      if (!this.userMovedView) {
        this.pinToTop();
      }
      this.loading = false;
    });
  }

  /**
   * Puts the caret at the start of the document and scrolls the viewport there. Called as each chunk
   * lands, so the top stays visible while the rest streams in.
   */
  public void pinToTop() {
    if (this.userMovedView) {
      return;
    }
    if (this.textPane.getCaretPosition() != 0) {
      this.textPane.setCaretPosition(0);
    }
    JScrollBar vertical = this.scrollPane.getVerticalScrollBar();
    if (vertical != null && vertical.getValue() != 0) {
      vertical.setValue(0);
    }
  }

  public void setEditable(boolean editable) {
    this.textPane.setEditable(editable);
  }

  /**
   * Wraps or unwraps at the view width, keeping the scrollbars honest: wrapped content never
   * scrolls sideways, unwrapped content does.
   */
  void setWrapped(boolean wrap) {
    this.textPane.setWrapped(wrap);
    this.scrollPane.setHorizontalScrollBarPolicy(wrap
        ? JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        : JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
  }

  public PrettyTextPane getTextPane() {
    return this.textPane;
  }

  /** Menu Copy: the editor holds exactly the message characters, so the default copy is exact. */
  void copySelection() {
    ActionMap actions = this.textPane.getActionMap();
    Object existing = actions.get(COPY_KEY);
    if (existing instanceof Action) {
      ((Action) existing).actionPerformed(
          new ActionEvent(this.textPane, ActionEvent.ACTION_PERFORMED, "copy"));
    } else {
      this.textPane.copy();
    }
  }

  @Override
  public void updateUI() {
    super.updateUI();
    if (this.textPane != null) {
      // ZAP republishes its fonts when the look and feel changes, and its own syntax highlighted text area
      // reads them again at that point. Without this the panel would keep the font it was built with for
      // the rest of the session.
      EditorTheme.apply(this.textPane, this.scrollPane, EditorTheme.palette());
      if (this.scrollPane != null) {
        // The font just changed, so a row is a different height and the wheel should move by that much.
        this.applyScrollUnit();
      }
    }
  }

  public void showLoading(String sizeHint) {
    PrettyViewPanel.onEdt(() -> this.noticeBar.showLoading(sizeHint));
  }

  public void showThresholdNotice(String detail) {
    PrettyViewPanel.onEdt(() -> this.noticeBar.showThresholdNotice(detail));
  }

  public void showFallbackNotice(String detail) {
    PrettyViewPanel.onEdt(() -> this.noticeBar.showFallbackNotice(detail));
  }

  public void clearNotice() {
    PrettyViewPanel.onEdt(() -> this.noticeBar.clear());
  }

  /** Releases this view's resources. Nothing ticks anymore; kept so unload has a hook. */
  public void dispose() {
  }

  private static void onEdt(Runnable task) {
    if (SwingUtilities.isEventDispatchThread()) {
      task.run();
    } else {
      SwingUtilities.invokeLater(task);
    }
  }
}

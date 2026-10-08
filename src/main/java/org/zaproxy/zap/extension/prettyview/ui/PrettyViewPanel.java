package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

public final class PrettyViewPanel
extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final long TICK_MILLIS = 150;
  private static final String COPY_KEY = "copy";
  private final RSyntaxTextArea textArea;
  private final JScrollPane scrollPane;
  private final PrettyNoticeBar noticeBar;
  private final PrettySearchBar searchBar;
  private final Timer tickTimer;
  /** True while a payload is streaming in, which is when the view is held at the top of the document. */
  private boolean loading;
  /** Set once the user scrolls or clicks during a load, after which the view is left entirely alone. */
  private boolean userMovedView;
  /** How the displayed text was broken into editor-sized lines, so a copy can put it back together. */
  private DisplayLineSplitter.Split split;
  private CopyAction copyAction;
  private PrettyMenu popupMenu;

  public PrettyViewPanel(RSyntaxTextArea textArea) {
    super(new BorderLayout());
    this.textArea = textArea;
    this.scrollPane = new RTextScrollPane(textArea);
    this.noticeBar = new PrettyNoticeBar();
    this.searchBar = new PrettySearchBar(textArea);
    EditorTheme.apply(textArea, this.scrollPane, EditorTheme.palette());
    JPanel header = new JPanel(new BorderLayout());
    header.add((Component)this.noticeBar, "North");
    header.add((Component)this.searchBar, "South");
    this.add((Component)header, "North");
    this.add((Component)this.scrollPane, "Center");
    this.tickTimer = new Timer(150, event -> this.noticeBar.advanceSpinner());
    this.tickTimer.setRepeats(true);
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
    this.watchForKeys(this.textArea);
    this.installScrolling();
    this.installPopupMenu();
    this.installSearchKeys();
  }

  /**
   * Makes the wheel and the arrow keys move the view by a line rather than by a handful of pixels.
   *
   * <p>Swing's default unit increment comes from the view, and on a text area that can be a few pixels,
   * which reads as scrolling that barely moves however hard the wheel is turned. Asking for a whole row
   * per unit, and for FlatLaf's smooth scrolling on top when ZAP is running its own look and feel, makes
   * the same physical gesture move a predictable amount of text.
   */
  private void installScrolling() {
    this.scrollPane.putClientProperty("JScrollPane.smoothScrolling", Boolean.TRUE);
    this.applyScrollUnit();
    this.scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent event) {
        PrettyViewPanel.this.applyScrollBlock();
      }
    });
  }

  private void applyScrollUnit() {
    int unit = Math.max(8, this.textArea.getFontMetrics(this.textArea.getFont()).getHeight());
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
    int unit = Math.max(8, this.textArea.getFontMetrics(this.textArea.getFont()).getHeight());
    int height = this.scrollPane.getViewport().getHeight();
    vertical.setBlockIncrement(Math.max(unit, height - unit));
  }

  /**
   * Gives the editor the right-click menu ZAP's request and response panels have.
   *
   * <p>The menu is rebuilt every time it opens rather than built once. The items are taken from the
   * editor's own action map, and the copy action in that map is replaced with the split-aware one the
   * first time a payload is shown; rebuilding on open means the menu always offers whatever is current,
   * so a right-click Copy hands back the response's characters rather than the editor's inserted line
   * breaks. Find entries sit alongside the clipboard ones, which is where the search is reached from when
   * the keyboard shortcut is not known.
   */
  private void installPopupMenu() {
    PrettyMenu menu = new PrettyMenu();
    this.textArea.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent event) {
        PrettyViewPanel.this.showPopup(event);
      }

      @Override
      public void mouseReleased(MouseEvent event) {
        PrettyViewPanel.this.showPopup(event);
      }
    });
    this.popupMenu = menu;
  }

  private void showPopup(MouseEvent event) {
    if (!event.isPopupTrigger()) {
      return;
    }
    if (this.textArea.getSelectionStart() == this.textArea.getSelectionEnd()) {
      int position = this.textArea.viewToModel2D(event.getPoint());
      if (position >= 0) {
        this.textArea.setCaretPosition(position);
      }
    }
    this.popupMenu.show(event.getComponent(), event.getX(), event.getY());
  }

  private final class PrettyMenu extends JPopupMenu {
    private static final long serialVersionUID = 1L;

    @Override
    public void show(Component invoker, int x, int y) {
      this.populate();
      super.show(invoker, x, y);
    }

    /** Fills the menu from the editor's current actions; runs each time the menu opens. */
    private void populate() {
      this.removeAll();
      this.add(PrettyViewPanel.this.actionItem("RTA.UndoAction", "Undo"));
      this.add(PrettyViewPanel.this.actionItem("RTA.RedoAction", "Redo"));
      this.addSeparator();
      this.add(PrettyViewPanel.this.actionItem("cut-to-clipboard", "Cut"));
      this.add(PrettyViewPanel.this.copyItem());
      this.add(PrettyViewPanel.this.actionItem("paste-from-clipboard", "Paste"));
      this.add(PrettyViewPanel.this.deleteItem());
      this.addSeparator();
      this.add(PrettyViewPanel.this.menuItem("Find...", KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), () -> PrettyViewPanel.this.openSearch()));
      this.add(PrettyViewPanel.this.menuItem("Find Next", KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), () -> PrettyViewPanel.this.searchBar.findNext()));
      this.add(PrettyViewPanel.this.menuItem("Find Previous", KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), () -> PrettyViewPanel.this.searchBar.findPrevious()));
      this.addSeparator();
      this.add(PrettyViewPanel.this.actionItem("select-all", "Select All"));
    }
  }

  private JMenuItem actionItem(String actionKey, String label) {
    Action action = this.textArea.getActionMap().get(actionKey);
    JMenuItem item = action == null ? new JMenuItem(label) : new JMenuItem(action);
    item.setText(label);
    return item;
  }

  private JMenuItem copyItem() {
    // Deliberately looks "copy" up at open time: it is the split-aware action once a payload is shown.
    Action action = this.textArea.getActionMap().get(COPY_KEY);
    JMenuItem item = action == null ? new JMenuItem("Copy") : new JMenuItem(action);
    item.setText("Copy");
    return item;
  }

  private JMenuItem deleteItem() {
    JMenuItem item = this.menuItem("Delete", null, () -> this.textArea.replaceSelection(""));
    item.setEnabled(this.textArea.getSelectionStart() != this.textArea.getSelectionEnd());
    return item;
  }

  private JMenuItem menuItem(String text, KeyStroke accelerator, Runnable action) {
    JMenuItem item = new JMenuItem(text);
    if (accelerator != null) {
      item.setAccelerator(accelerator);
    }
    item.addActionListener(event -> action.run());
    return item;
  }

  /** Ctrl+F opens the find bar, F3 walks the matches, and Escape puts the bar away again. */
  private void installSearchKeys() {
    this.bind(this.textArea, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "pretty-find", this::openSearch);
    this.bind(this.textArea, KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "pretty-find-next", this.searchBar::findNext);
    this.bind(this.textArea, KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "pretty-find-previous", this.searchBar::findPrevious);
    this.bind(this.textArea, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "pretty-find-close", () -> {
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

  private void openSearch() {
    this.searchBar.open(this.textArea.getSelectedText());
  }

  private void watchForClicks(Component viewport) {
    viewport.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent event) {
        PrettyViewPanel.this.noteUserMovedView();
      }
    });
  }

  private void watchForKeys(JTextArea component) {
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
    if (this.textArea.getCaretPosition() != 0) {
      this.textArea.setCaretPosition(0);
    }
    JScrollBar vertical = this.scrollPane.getVerticalScrollBar();
    if (vertical != null && vertical.getValue() != 0) {
      vertical.setValue(0);
    }
  }

  public void setEditable(boolean editable) {
    this.textArea.setEditable(editable);
  }

  public RSyntaxTextArea getTextArea() {
    return this.textArea;
  }

  public JScrollPane getScrollPane() {
    return this.scrollPane;
  }

  /**
   * Reminds the panel how the displayed text was broken up, so a copy hands back the characters as the
   * response holds them rather than as the editor shows them.
   */
  public void setSplit(DisplayLineSplitter.Split split) {
    this.split = split;
    if (this.copyAction != null) {
      return;
    }
    this.copyAction = new CopyAction();
    ActionMap actions = this.textArea.getActionMap();
    Object existing = actions.get(COPY_KEY);
    if (existing instanceof Action) {
      this.copyAction.delegate = (Action) existing;
    }
    actions.put(COPY_KEY, this.copyAction);
    this.textArea.registerKeyboardAction(
        this.copyAction,
        KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
        JComponent.WHEN_FOCUSED);
  }

  private String selectedTextAsFormatted() {
    if (this.split == null) {
      return null;
    }
    int from = this.textArea.getSelectionStart();
    int to = this.textArea.getSelectionEnd();
    if (from == to) {
      return null;
    }
    if (from > to) {
      int swap = from;
      from = to;
      to = swap;
    }
    return this.split.originalRange(from, to);
  }

  /** Copies through the split so the clipboard holds the response's own characters. */
  private final class CopyAction extends AbstractAction {
    private static final long serialVersionUID = 1L;
    private Action delegate;

    @Override
    public void actionPerformed(ActionEvent event) {
      String formatted = PrettyViewPanel.this.selectedTextAsFormatted();
      if (formatted != null) {
        try {
          Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
              new StringSelection(formatted), null);
          return;
        } catch (HeadlessException | IllegalStateException | SecurityException e) {
          // Fall through to whatever the editor would have done on its own.
        }
      }
      if (this.delegate != null) {
        this.delegate.actionPerformed(event);
      }
    }
  }

  @Override
  public void updateUI() {
    super.updateUI();
    if (this.textArea != null) {
      // ZAP republishes its fonts when the look and feel changes, and its own syntax highlighted text area
      // reads them again at that point. Without this the panel would keep the font it was built with for
      // the rest of the session.
      EditorTheme.apply(this.textArea, this.scrollPane, EditorTheme.palette());
      if (this.scrollPane != null) {
        // The font just changed, so a row is a different height and the wheel should move by that much.
        this.applyScrollUnit();
      }
    }
  }

  public PrettyNoticeBar getNoticeBar() {
    return this.noticeBar;
  }

  public void showLoading(String sizeHint) {
    PrettyViewPanel.onEdt(() -> {
      this.startTicking();
      this.noticeBar.showLoading(sizeHint);
    });
  }

  public void showThresholdNotice(String detail) {
    PrettyViewPanel.onEdt(() -> {
      this.stopTicking();
      this.noticeBar.showThresholdNotice(detail);
    });
  }

  public void showFallbackNotice(String detail) {
    PrettyViewPanel.onEdt(() -> {
      this.stopTicking();
      this.noticeBar.showFallbackNotice(detail);
    });
  }

  public void clearNotice() {
    PrettyViewPanel.onEdt(() -> {
      this.stopTicking();
      this.noticeBar.clear();
    });
  }

  private void startTicking() {
    if (!this.tickTimer.isRunning()) {
      this.tickTimer.start();
    }
  }

  private void stopTicking() {
    if (this.tickTimer.isRunning()) {
      this.tickTimer.stop();
    }
  }

  private static void onEdt(Runnable task) {
    if (SwingUtilities.isEventDispatchThread()) {
      task.run();
    } else {
      SwingUtilities.invokeLater(task);
    }
  }
}


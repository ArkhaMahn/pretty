package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.extension.ExtensionPopupMenuItem;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.view.MainPopupMenu;
import org.parosproxy.paros.view.View;
import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.extension.httppanel.view.text.HttpPanelTextView;
import org.zaproxy.zap.view.messagecontainer.MessageContainer;
import org.zaproxy.zap.view.messagecontainer.http.DefaultSingleHttpMessageContainer;
import org.zaproxy.zap.view.popup.PopupMenuUtils;
import org.zaproxy.zap.view.popup.PopupMenuUtils.PopupMenuInvokerWrapper;

public final class PrettyViewPanel
extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final int TICK_MILLIS = 150;  private static final String COPY_KEY = "copy";
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
  /** The current ZAP-action section, rebuilt on every show and dropped on the next. */
  private final List<Component> zapSection = new ArrayList<Component>();
  /** The model providing message data for ZAP actions. */
  private final PrettyViewModel model;

  public PrettyViewPanel(RSyntaxTextArea textArea, PrettyViewModel model) {
    super(new BorderLayout());
    this.textArea = textArea;
    this.model = model;
    this.scrollPane = new RTextScrollPane(textArea);
    this.noticeBar = new PrettyNoticeBar();
    this.searchBar = new PrettySearchBar(textArea);
    EditorTheme.apply(textArea, this.scrollPane, EditorTheme.palette());
    JPanel header = new JPanel(new BorderLayout());
    header.add((Component)this.noticeBar, "North");
    header.add((Component)this.searchBar, "South");
    this.add((Component)header, "North");
    this.add((Component)this.scrollPane, "Center");
    this.tickTimer = new Timer(TICK_MILLIS, event -> this.noticeBar.advanceSpinner());
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
    int unit = Math.max(8, this.textArea.getLineHeight());
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
    int unit = Math.max(8, this.textArea.getLineHeight());
    int height = this.scrollPane.getViewport().getHeight();
    vertical.setBlockIncrement(Math.max(unit, height - unit));
  }

  /**
   * The single right-click menu: working text operations plus ZAP's own message actions behind
   * one entry. Everything here acts directly on this editor, so clicks cannot miss.
   */
  private void installPopupMenu() {
    this.textArea.setComponentPopupMenu(this.createComponentPopupMenu());
    // Caret follows a right-click with no selection (standard text behaviour); the menu itself
    // is shown by Swing, so this listener never shows anything and no second menu can appear.
    this.textArea.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent event) {
        PrettyViewPanel.this.trackPopupClick(event);
      }

      @Override
      public void mouseReleased(MouseEvent event) {
        PrettyViewPanel.this.trackPopupClick(event);
      }
    });
  }

  private void trackPopupClick(MouseEvent event) {
    if (!SwingUtilities.isRightMouseButton(event)) {
      return;
    }
    if (this.textArea.getSelectionStart() == this.textArea.getSelectionEnd()) {
      int position = this.textArea.viewToModel2D(event.getPoint());
      if (position >= 0) {
        this.textArea.setCaretPosition(position);
      }
    }
  }

  private JPopupMenu createComponentPopupMenu() {
    JPopupMenu menu = new JPopupMenu();
    RSyntaxTextArea area = this.textArea;

    JMenuItem copyItem = new JMenuItem("Copy");
    copyItem.addActionListener(e -> this.copyThroughSplit());
    menu.add(copyItem);

    JMenuItem cutItem = new JMenuItem("Cut");
    cutItem.addActionListener(e -> PrettyViewPanel.cutSelection(area));
    menu.add(cutItem);

    JMenuItem pasteItem = new JMenuItem("Paste");
    pasteItem.addActionListener(e -> PrettyViewPanel.pasteClipboard(area));
    menu.add(pasteItem);

    JMenuItem deleteItem = new JMenuItem("Delete");
    deleteItem.addActionListener(e -> {
      if (area.isEditable() && area.getSelectionStart() != area.getSelectionEnd()) {
        area.replaceSelection("");
      }
    });
    menu.add(deleteItem);

    menu.addSeparator();

    JMenuItem selectAllItem = new JMenuItem("Select All");
    selectAllItem.addActionListener(e -> area.selectAll());
    menu.add(selectAllItem);

    JMenuItem findItem = new JMenuItem("Find...");
    findItem.addActionListener(e -> this.openSearch());
    menu.add(findItem);

    JMenuItem findNextItem = new JMenuItem("Find Next");
    findNextItem.addActionListener(e -> this.findNext());
    menu.add(findNextItem);

    JMenuItem findPrevItem = new JMenuItem("Find Previous");
    findPrevItem.addActionListener(e -> this.findPrevious());
    menu.add(findPrevItem);

    menu.addSeparator();

    menu.addPopupMenuListener(new PopupMenuListener() {
      @Override
      public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
        // Core parity: hand focus back to the editor before the menu shows.
        if (!area.isFocusOwner()) {
          area.requestFocusInWindow();
        }
        boolean hasSelection = area.getSelectionStart() != area.getSelectionEnd();
        copyItem.setEnabled(hasSelection);
        cutItem.setEnabled(area.isEditable() && hasSelection);
        pasteItem.setEnabled(area.isEditable());
        deleteItem.setEnabled(area.isEditable() && hasSelection);
        PrettyViewPanel.this.rebuildZapSection(menu);
      }

      @Override
      public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
      }

      @Override
      public void popupMenuCanceled(PopupMenuEvent event) {
      }
    });
    return menu;
  }

  /** Copies through the split mapping so the clipboard holds the message's own characters. */
  private void copyThroughSplit() {
    if (this.copyAction != null) {
      this.copyAction.actionPerformed(
          new ActionEvent(this.textArea, ActionEvent.ACTION_PERFORMED, "copy"));
    } else {
      PrettyViewPanel.copyLiteral(this.textArea);
    }
  }

  /**
   * Rebuilds the ZAP message-action section at the end of the menu: the enabled entries of ZAP's
   * own popup for the current message, cloned in so text operations and message actions share one
   * menu. Runs on every show, so the section always matches the message under the cursor.
   */
  private void rebuildZapSection(JPopupMenu menu) {
    for (Component doomed : this.zapSection) {
      menu.remove(doomed);
    }
    this.zapSection.clear();
    Message message = this.model.getMessage();
    if (!(message instanceof HttpMessage)) {
      return;
    }
    MainPopupMenu popup;
    PopupMenuInvokerWrapper wrapper;
    try {
      View view = View.getSingleton();
      if (view == null) {
        return;
      }
      popup = view.getPopupMenu();
      MessageContainer<?> container = new DefaultSingleHttpMessageContainer(
          HttpPanelTextView.DEFAULT_MESSAGE_CONTAINER_NAME,
          this.textArea,
          (HttpMessage) message);
      wrapper = PopupMenuUtils.getPopupMenuInvokerWrapper(container);
    } catch (RuntimeException e) {
      return;
    }
    List<ExtensionPopupMenuItem> enabled = new ArrayList<ExtensionPopupMenuItem>();
    for (Component component : popup.getComponents()) {
      if (!PopupMenuUtils.isExtensionPopupMenuItem(component)) {
        continue;
      }
      ExtensionPopupMenuItem item = (ExtensionPopupMenuItem) component;
      boolean on;
      try {
        on = wrapper.isEnable(item);
      } catch (RuntimeException e) {
        continue;
      }
      if (on && !TEXT_OP_LABELS.contains(item.getText())) {
        enabled.add(item);
      }
    }
    if (enabled.isEmpty()) {
      return;
    }
    enabled.sort((first, second) -> Integer.compare(second.getWeight(), first.getWeight()));
    boolean safeMode = PrettyViewPanel.isSafeMode();
    Map<String, JMenu> submenus = new LinkedHashMap<String, JMenu>();
    for (ExtensionPopupMenuItem item : enabled) {
      if (item.precedeWithSeparator()) {
        JSeparator separator = new JSeparator();
        menu.add(separator);
        this.zapSection.add(separator);
      }
      JMenuItem clone = PrettyViewPanel.cloneItem(item, safeMode);
      String parent = item.getParentMenuName();
      if (parent == null || parent.isEmpty()) {
        menu.add(clone);
        this.zapSection.add(clone);
      } else {
        JMenu submenu = submenus.get(parent);
        if (submenu == null) {
          submenu = new JMenu(parent);
          submenus.put(parent, submenu);
          menu.add(submenu);
          this.zapSection.add(submenu);
        }
        submenu.add(clone);
      }
      if (item.succeedWithSeparator()) {
        JSeparator separator = new JSeparator();
        menu.add(separator);
        this.zapSection.add(separator);
      }
    }
    PopupMenuUtils.removeConsecutiveSeparators(menu);
    PopupMenuUtils.removeTopAndBottomSeparators(menu);
  }

  /** Text-operation labels already heading this menu; never duplicated from ZAP's popup. */
  private static final Set<String> TEXT_OP_LABELS = Set.of(
      "Copy", "Cut", "Paste", "Delete", "Select All",
      "Find...", "Find Next", "Find Previous");

  private static JMenuItem cloneItem(ExtensionPopupMenuItem item, boolean safeMode) {
    JMenuItem clone = new JMenuItem(item.getText(), item.getIcon());
    for (ActionListener listener : item.getActionListeners()) {
      clone.addActionListener(listener);
    }
    clone.setEnabled(item.isEnabled() && (!safeMode || item.isSafe()));
    return clone;
  }

  private static boolean isSafeMode() {
    try {
      return Control.getSingleton().getMode().equals(Control.Mode.safe);
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Copies the selection to the clipboard; a no-op without a selection. */
  private static void copyLiteral(JTextComponent area) {
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

  /** Opens the find bar; the popup's Find entry and Ctrl+F call this. */
  void openSearch() {
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
    // The break offsets only describe the text as loaded; once edited they stop meaning
    // anything, so an edited selection copies literally (see CopyAction).
    if (!this.split.isDisplayText(this.textArea.getText())) {
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
    try {
      return this.split.originalRange(from, to);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Copies through the split so the clipboard holds the message's own characters. */
  private final class CopyAction extends AbstractAction {
    private static final long serialVersionUID = 1L;
    private Action delegate;

    @Override
    public void actionPerformed(ActionEvent event) {
      RSyntaxTextArea area = PrettyViewPanel.this.textArea;
      String selected = area.getSelectedText();
      if (selected == null || selected.isEmpty()) {
        // Nothing selected: behave like the default copy (a no-op).
        if (this.delegate != null) {
          this.delegate.actionPerformed(event);
        }
        return;
      }
      String formatted = PrettyViewPanel.this.selectedTextAsFormatted();
      // Never put an empty string on the clipboard for a non-empty selection: when the split
      // mapping comes back empty (edited text, or a break-only span) the literal selection is
      // what the user asked for.
      if (formatted != null && !formatted.isEmpty()) {
        try {
          Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
              new StringSelection(formatted), null);
          return;
        } catch (HeadlessException | IllegalStateException | SecurityException e) {
          // Fall through to the literal copy below.
        }
      }
      if (this.delegate != null) {
        this.delegate.actionPerformed(event);
      } else {
        area.copy();
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

  /**
   * Releases panel resources. Stops the spinner so a detached panel no longer fires every
   * 150 ms after its view has been unloaded.
   */
  public void dispose() {
    this.stopTicking();
  }

  private static void onEdt(Runnable task) {
    if (SwingUtilities.isEventDispatchThread()) {
      task.run();
    } else {
      SwingUtilities.invokeLater(task);
    }
  }
}


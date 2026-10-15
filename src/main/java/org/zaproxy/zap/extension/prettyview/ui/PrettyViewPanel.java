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
import java.util.function.BooleanSupplier;
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
import javax.swing.text.JTextComponent;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
  private static final Logger LOGGER = LogManager.getLogger(PrettyViewPanel.class);
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
  /** The current ZAP-action tail, rebuilt on every show and dropped on the next. */
  private final List<Component> zapSection = new ArrayList<Component>();
  /** The model providing message data for ZAP actions. */
  private final PrettyViewModel model;

  public PrettyViewPanel(RSyntaxTextArea textArea, PrettyViewModel model) {
    super(new BorderLayout());
    this.textArea = textArea;
    this.model = model;
    // RSyntax's own popup (Undo/Redo/Cut/Copy/Paste/Folding) plus ZAP's message actions
    // appended below it: one menu holding everything, rebuilt on every show.
    this.replacePopupTextActions(this.textArea.getPopupMenu());
    this.textArea.getPopupMenu().addPopupMenuListener(new PopupMenuListener() {
      @Override
      public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
        if (!textArea.isFocusOwner()) {
          textArea.requestFocusInWindow();
        }
        refreshReplacedItems();
        rebuildZapSection();
        emitOrderedMenu();
      }

      @Override
      public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
      }

      @Override
      public void popupMenuCanceled(PopupMenuEvent event) {
      }
    });
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
   * Rebuilds the ZAP message-action tail at the end of RSyntax's own popup: the enabled entries
   * of ZAP's popup for the current message, cloned in, so text operations and message actions
   * share one menu. Runs on every show, so the tail always matches the message under the cursor.
   */
  /** Fresh message-action entries per show: top-level items plus submenu groups. */
  private final List<ExtensionPopupMenuItem> zapTop = new ArrayList<ExtensionPopupMenuItem>();
  private final Map<String, ZapGroup> zapGroups = new LinkedHashMap<String, ZapGroup>();

  /**
   * Collects the ZAP message-action entries for the current message: the enabled registry
   * entries, preserving each addon's built submenu structure. Runs on every show, so the
   * entries always match the message under the cursor.
   */
  private void rebuildZapSection() {
    this.zapTop.clear();
    this.zapGroups.clear();
    Message message = this.model.getMessage();
    if (!(message instanceof HttpMessage)) {
      return;
    }
    PopupMenuInvokerWrapper wrapper;
    List<ExtensionPopupMenuItem> seen = new ArrayList<ExtensionPopupMenuItem>();
    try {
      View view = View.getSingleton();
      if (view == null || view.getPopupList() == null) {
        return;
      }
      MessageContainer<?> container = new DefaultSingleHttpMessageContainer(
          HttpPanelTextView.DEFAULT_MESSAGE_CONTAINER_NAME,
          this.textArea,
          (HttpMessage) message);
      wrapper = PopupMenuUtils.getPopupMenuInvokerWrapper(container);
      // Snapshot: ZAP mutates the registry Vector on other threads, so iterating it live
      // throws ConcurrentModificationException. Vector.toArray is synchronized, making the
      // copy atomic.
      List<JMenuItem> registered = new ArrayList<JMenuItem>(view.getPopupList());
      PrettyViewPanel.collectZapEntries(
          registered, wrapper, this.zapTop, this.zapGroups, seen);
      LOGGER.debug("PrettyView ZAP tail: registry={} top={} groups={}",
          Integer.valueOf(registered.size()), Integer.valueOf(this.zapTop.size()),
          Integer.valueOf(this.zapGroups.size()));
    } catch (RuntimeException e) {
      LOGGER.warn("PrettyView ZAP tail: failed to enumerate ZAP popup actions", e);
    }
  }

  /**
   * Lays the whole menu out in core order: text entries and message actions merged by weight,
   * heaviest first, exactly how ZAP orders its own popup. Text entries carry core's own text
   * weights, so they land mid-menu instead of lumped at the top.
   */
  private void emitOrderedMenu() {
    JPopupMenu menu = this.textArea.getPopupMenu();
    if (menu == null) {
      return;
    }
    for (Component doomed : this.zapSection) {
      menu.remove(doomed);
    }
    this.zapSection.clear();
    for (ReplacedItem replaced : this.replacedItems) {
      menu.remove(replaced.item);
    }
    for (Component component : menu.getComponents()) {
      if (component instanceof JSeparator) {
        menu.remove(component);
      }
    }
    boolean safeMode = PrettyViewPanel.isSafeMode();
    List<ZapSegment> segments = new ArrayList<ZapSegment>();
    for (ReplacedItem replaced : this.replacedItems) {
      segments.add(new ZapSegment(replaced.weight, replaced.item, null, null));
    }
    for (ExtensionPopupMenuItem item : this.zapTop) {
      segments.add(new ZapSegment(item.getWeight(), null, item, null));
    }
    for (ZapGroup group : this.zapGroups.values()) {
      group.members.sort((first, second) -> Integer.compare(second.getWeight(), first.getWeight()));
      segments.add(new ZapSegment(group.weight(), null, null, group));
    }
    segments.sort((first, second) -> Integer.compare(second.weight, first.weight));
    for (ZapSegment segment : segments) {
      if (segment.group == null) {
        if (segment.zapItem != null) {
          PrettyViewPanel.emitZapItem(menu, segment.zapItem, safeMode, this.zapSection);
        } else {
          menu.add(segment.direct);
          this.zapSection.add(segment.direct);
        }
      } else {
        JMenu submenu = new JMenu(segment.group.title);
        for (ExtensionPopupMenuItem member : segment.group.members) {
          PrettyViewPanel.emitZapItem(submenu, member, safeMode, null);
        }
        PopupMenuUtils.removeConsecutiveSeparators(submenu);
        menu.add(submenu);
        this.zapSection.add(submenu);
      }
    }
    PopupMenuUtils.removeConsecutiveSeparators(menu);
    PopupMenuUtils.removeTopAndBottomSeparators(menu);
  }

  /** One menu row: a ready text entry, a message entry to clone, or a whole submenu. */
  private static final class ZapSegment {
    final int weight;
    final JMenuItem direct;
    final ExtensionPopupMenuItem zapItem;
    final ZapGroup group;

    ZapSegment(int weight, JMenuItem direct, ExtensionPopupMenuItem zapItem, ZapGroup group) {
      this.weight = weight;
      this.direct = direct;
      this.zapItem = zapItem;
      this.group = group;
    }
  }

  /** A submenu kept exactly as its addon built it: title plus member entries in order. */
  private static final class ZapGroup {
    final String title;
    final List<ExtensionPopupMenuItem> members = new ArrayList<ExtensionPopupMenuItem>();

    ZapGroup(String title) {
      this.title = title;
    }

    int weight() {
      int weight = Integer.MIN_VALUE;
      for (ExtensionPopupMenuItem member : this.members) {
        weight = Math.max(weight, member.getWeight());
      }
      return weight;
    }
  }

  private static void emitZapItem(
      JComponent parent, ExtensionPopupMenuItem item, boolean safeMode, List<Component> tracked) {
    if (item.precedeWithSeparator()) {
      JSeparator separator = new JSeparator();
      parent.add(separator);
      if (tracked != null) {
        tracked.add(separator);
      }
    }
    JMenuItem clone = PrettyViewPanel.cloneItem(item, safeMode);
    parent.add(clone);
    if (tracked != null) {
      tracked.add(clone);
    }
    if (item.succeedWithSeparator()) {
      JSeparator separator = new JSeparator();
      parent.add(separator);
      if (tracked != null) {
        tracked.add(separator);
      }
    }
  }

  /**
   * Collects the enabled message actions from ZAP's registered popup items, preserving each
   * addon's built submenu structure: entries already grouped under a submenu root stay under a
   * submenu of that title, instead of being flattened and regrouped. Reads the stable
   * registration list (not the live menu's children, which only exist while a menu is showing),
   * so the tail is complete on every show. Identity-deduplicated: an entry registered both
   * standalone and inside a submenu appears once.
   */
  private static void collectZapEntries(
      List<? extends Component> components,
      PopupMenuInvokerWrapper wrapper,
      List<ExtensionPopupMenuItem> top,
      Map<String, ZapGroup> groups,
      List<ExtensionPopupMenuItem> seen) {
    for (Component component : components) {
      if (component instanceof JMenu && !(component instanceof ExtensionPopupMenuItem)) {
        // Submenu roots keep their children in their popup, not in themselves.
        JMenu root = (JMenu) component;
        String title = root.getText();
        if (title == null || title.isEmpty()) {
          continue;
        }
        for (Component child :
            java.util.Arrays.asList(root.getPopupMenu().getComponents())) {
          PrettyViewPanel.considerZapItem(child, wrapper, top, groups, seen, title);
        }
        continue;
      }
      PrettyViewPanel.considerZapItem(component, wrapper, top, groups, seen, null);
    }
  }

  private static void considerZapItem(
      Component component,
      PopupMenuInvokerWrapper wrapper,
      List<ExtensionPopupMenuItem> top,
      Map<String, ZapGroup> groups,
      List<ExtensionPopupMenuItem> seen,
      String forceGroup) {
    if (!PopupMenuUtils.isExtensionPopupMenuItem(component)) {
      return;
    }
    ExtensionPopupMenuItem item = (ExtensionPopupMenuItem) component;
    if (seen.contains(item)) {
      return;
    }
    seen.add(item);
    boolean on;
    try {
      on = wrapper.isEnable(item);
    } catch (RuntimeException e) {
      return;
    }
    if (!on
        || item.getText() == null
        || item.getText().isEmpty()
        || TEXT_OP_LABELS.contains(item.getText())) {
      return;
    }
    // Entries already grouped by their addon stay under that title; loose entries honour the
    // declared parent menu, if any.
    String group = forceGroup != null
        ? forceGroup
        : item.getParentMenuName();
    if (group == null || group.isEmpty()) {
      top.add(item);
      return;
    }
    ZapGroup existing = groups.get(group);
    if (existing == null) {
      existing = new ZapGroup(group);
      groups.put(group, existing);
    }
    existing.members.add(item);
  }

  /** Labels RSyntax's own popup already provides; never duplicated from ZAP's popup. */
  private static final Set<String> TEXT_OP_LABELS = Set.of(
      "Copy", "Cut", "Paste", "Delete", "Select All",
      "Find...", "Find Next", "Find Previous", "Undo", "Redo");

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

  /** Our replacements for RSyntax's text entries, refreshed on every show. */
  private final List<ReplacedItem> replacedItems = new ArrayList<ReplacedItem>();

  /** A menu entry acting directly on this editor. */
  private static final class ReplacedItem {
    final JMenuItem item;
    final BooleanSupplier enabled;
    final int weight;

    ReplacedItem(JMenuItem item, BooleanSupplier enabled, int weight) {
      this.item = item;
      this.enabled = enabled;
      this.weight = weight;
    }
  }

  /**
   * Text-operation weights matching ZAP's own text entries (HttpPanelSyntaxHighlightTextArea
   * registers Undo 19060, Redo 19040, Cut 17080, Copy 17060, Paste 17040, Delete 17020,
   * Select All 15050), so the merged menu orders exactly like core's. Verified against the
   * ZAP 2.17.0 bytecode; revisit if ZAP renumbers them.
   */

  private JMenuItem undoItem;
  private JMenuItem redoItem;

  /**
   * RSyntax's own Cut/Copy/Paste resolve their target through focus state, which has already
   * moved to the open menu - so menu clicks silently miss. Its entries are therefore swapped for
   * fresh items with the same labels that act directly on this editor; order, separators and the
   * Folding submenu are untouched. Unmatched entries (other locales) keep stock behaviour.
   */
  private void replacePopupTextActions(JPopupMenu menu) {
    if (menu == null) {
      return;
    }
    Component[] components = menu.getComponents();
    List<Integer> at = new ArrayList<Integer>();
    List<String> kinds = new ArrayList<String>();
    for (int i = 0; i < components.length; ++i) {
      if (!(components[i] instanceof JMenuItem) || components[i] instanceof JMenu) {
        continue;
      }
      String kind = PrettyViewPanel.textActionKind(((JMenuItem) components[i]).getText());
      if (kind != null) {
        at.add(Integer.valueOf(i));
        kinds.add(kind);
      }
    }
    for (int s = at.size() - 1; s >= 0; --s) {
      JMenuItem replacement = this.buildReplacement(kinds.get(s));
      if (replacement == null) {
        continue;
      }
      menu.remove(at.get(s).intValue());
      menu.add(replacement, at.get(s).intValue());
    }
    // Core's reference menu has no Folding entry; drop RSyntax's submenu for parity.
    for (Component component : menu.getComponents()) {
      if (component instanceof JMenu && "Folding".equals(((JMenu) component).getText())) {
        menu.remove(component);
        break;
      }
    }
  }

  private static String textActionKind(String text) {
    if (text == null) {
      return null;
    }
    switch (text) {
      case "Undo":
      case "Can't Undo":
        return "undo";
      case "Redo":
      case "Can't Redo":
        return "redo";
      case "Cut":
        return "cut";
      case "Copy":
        return "copy";
      case "Paste":
        return "paste";
      case "Delete":
        return "delete";
      case "Select All":
        return "selectall";
      default:
        return null;
    }
  }

  private JMenuItem buildReplacement(String kind) {
    RSyntaxTextArea area = this.textArea;
    switch (kind) {
      case "undo":
        this.undoItem = this.register(new JMenuItem("Undo"), area::undoLastAction,
            area::canUndo, 19060);
        return this.undoItem;
      case "redo":
        this.redoItem = this.register(new JMenuItem("Redo"), area::redoLastAction,
            area::canRedo, 19040);
        return this.redoItem;
      case "cut":
        return this.register(new JMenuItem("Cut"), () -> PrettyViewPanel.cutSelection(area),
            () -> area.isEditable() && PrettyViewPanel.hasSelection(area), 17080);
      case "copy":
        return this.register(new JMenuItem("Copy"), this::copyViaSplit,
            () -> PrettyViewPanel.hasSelection(area), 17060);
      case "paste":
        return this.register(new JMenuItem("Paste"), () -> PrettyViewPanel.pasteClipboard(area),
            area::isEditable, 17040);
      case "delete":
        return this.register(new JMenuItem("Delete"), () -> {
          if (area.isEditable() && PrettyViewPanel.hasSelection(area)) {
            area.replaceSelection("");
          }
        }, () -> area.isEditable() && PrettyViewPanel.hasSelection(area), 17020);
      case "selectall":
        return this.register(new JMenuItem("Select All"), area::selectAll, () -> true, 15050);
      default:
        return null;
    }
  }

  private JMenuItem register(
      JMenuItem item, Runnable action, BooleanSupplier enabled, int weight) {
    item.addActionListener(e -> action.run());
    this.replacedItems.add(new ReplacedItem(item, enabled, weight));
    return item;
  }

  private void refreshReplacedItems() {
    RSyntaxTextArea area = this.textArea;
    if (this.undoItem != null) {
      this.undoItem.setText(area.canUndo() ? "Undo" : "Can't Undo");
    }
    if (this.redoItem != null) {
      this.redoItem.setText(area.canRedo() ? "Redo" : "Can't Redo");
    }
    for (ReplacedItem replaced : this.replacedItems) {
      try {
        replaced.item.setEnabled(replaced.enabled.getAsBoolean());
      } catch (RuntimeException e) {
        replaced.item.setEnabled(true);
      }
    }
  }

  private static boolean hasSelection(JTextComponent area) {
    return area.getSelectionStart() != area.getSelectionEnd();
  }

  /** Menu Copy: split-aware once a payload is shown, literal before the first load. */
  private void copyViaSplit() {
    if (this.copyAction != null) {
      this.copyAction.actionPerformed(
          new ActionEvent(this.textArea, ActionEvent.ACTION_PERFORMED, "copy"));
    } else {
      PrettyViewPanel.copyLiteral(this.textArea);
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


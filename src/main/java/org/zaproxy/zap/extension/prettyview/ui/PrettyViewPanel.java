package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
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
import javax.swing.JPanel;
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
  private final Timer tickTimer;
  /** True while a payload is streaming in, which is when the view is held at the top of the document. */
  private boolean loading;
  /** Set once the user scrolls or clicks during a load, after which the view is left entirely alone. */
  private boolean userMovedView;
  /** How the displayed text was broken into editor-sized lines, so a copy can put it back together. */
  private DisplayLineSplitter.Split split;
  private CopyAction copyAction;

  public PrettyViewPanel(RSyntaxTextArea textArea) {
    super(new BorderLayout());
    this.textArea = textArea;
    this.scrollPane = new RTextScrollPane(textArea);
    this.noticeBar = new PrettyNoticeBar();
    EditorTheme.apply(textArea, this.scrollPane, EditorTheme.palette());
    this.add((Component)this.noticeBar, "North");
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


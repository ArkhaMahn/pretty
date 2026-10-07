package org.zaproxy.zap.extension.prettyview.ui;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.apache.commons.configuration.FileConfiguration;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelView;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModelEvent;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModelListener;
import org.zaproxy.zap.extension.prettyview.async.PrettyWorker;
import org.zaproxy.zap.extension.prettyview.formatters.PrettyResult;
import org.zaproxy.zap.extension.prettyview.formatters.UniversalPrettifierManager;

public class CustomPrettyView
implements HttpPanelView,
HttpPanelViewModelListener {
  private static final int POSITION = 2;
  private final String name;
  private final String captionName;
  private final boolean request;
  private final PrettyViewModel model;
  private final RSyntaxTextArea textArea;
  private final PrettyViewPanel panel;
  private final UniversalPrettifierManager prettifierManager;
  private final PrettyWorker worker;
  private final AtomicInteger generation = new AtomicInteger();
  private boolean loading;
  private String parentConfigurationKey;
  private String lastFormatted = "";
  private DisplayLineSplitter.Split split;

  public CustomPrettyView(String name, String captionName, boolean request) {
    this.name = Objects.requireNonNull(name, "name");
    this.captionName = Objects.requireNonNull(captionName, "captionName");
    this.request = request;
    this.prettifierManager = new UniversalPrettifierManager();
    this.worker = new PrettyWorker();
    this.model = new PrettyViewModel(request);
    this.textArea = this.createTextArea();
    this.panel = new PrettyViewPanel(this.textArea);
    this.model.addHttpPanelViewModelListener(this);
  }

  private RSyntaxTextArea createTextArea() {
    RSyntaxTextArea area = new RSyntaxTextArea();
    LargePayloadPolicy.applyBaseline(area);
    area.setEditable(true);
    area.setText("");
    EditorTheme.apply(area, null, EditorTheme.palette());
    return area;
  }

  @Override
  public void dataChanged(HttpPanelViewModelEvent event) {
    this.scheduleRefresh();
  }

  private void scheduleRefresh() {
    int ticket = this.generation.incrementAndGet();
    String raw = this.model.getData();
    String contentType = this.model.getContentType();
    if (raw.isEmpty()) {
      this.worker.cancelPending();
      this.applyEmpty(ticket);
      return;
    }
    this.panel.showLoading(CustomPrettyView.sizeHint(raw.length()));
    Consumer<PrettyResult> onResult = result -> this.applyOnEdt(ticket, (PrettyResult)result);
    this.worker.submit(raw, contentType, this.prettifierManager, onResult);
  }

  private void applyEmpty(int ticket) {
    CustomPrettyView.onEdt(() -> {
      if (ticket != this.generation.get()) {
        return;
      }
      this.panel.clearNotice();
      this.applyIfCurrent(ticket, PrettyResult.formatted("", null));
    });
  }

  private void applyOnEdt(int ticket, PrettyResult result) {
    if (SwingUtilities.isEventDispatchThread()) {
      this.applyIfCurrent(ticket, result);
    } else {
      SwingUtilities.invokeLater(() -> this.applyIfCurrent(ticket, result));
    }
  }

  private void applyIfCurrent(int ticket, PrettyResult result) {
    if (ticket != this.generation.get()) {
      return;
    }
    String text = result.getText();
    this.textArea.setToolTipText(null);
    DisplayLineSplitter.Split split = DisplayLineSplitter.split(text);
    this.split = split;
    this.panel.setSplit(split);
    // The editor is given the split text and so is judged on the split text. Deciding from the formatted
    // text instead would read a 54 331-char line off a document that no longer holds one, and would strip
    // the highlighting off a payload whose every line now fits in a screenful.
    LargePayloadPolicy.applyForSize(this.textArea, split);
    this.updateNotice(result);
    this.loading = true;
    JTextArea body = this.panel.getTextArea();
    body.setEditable(false);
    this.panel.beginLoad();
    ChunkedTextLoader.load(body, split.displayText(), LargePayloadPolicy.syntaxStyleFor(split, result.getFormat()), () -> ticket == this.generation.get(), this.panel::pinToTop, () -> {
      if (ticket != this.generation.get()) {
        return;
      }
      this.lastFormatted = split.displayText();
      this.loading = false;
      this.panel.getTextArea().setEditable(true);
      this.panel.endLoad();
    });
  }

  private void updateNotice(PrettyResult result) {
    this.updateNotice(result, null);
  }

  private void updateNotice(PrettyResult result, String extra) {
    StringBuilder message = new StringBuilder();
    if (extra != null && !extra.isEmpty()) {
        message.append(extra);
    }
    String note = CustomPrettyView.capitalise(result.getNote());
    if (!note.isEmpty()) {
      if (message.length() > 0) {
        message.append(' ');
      }
      message.append(note);
    }
    if (message.length() == 0) {
      this.panel.clearNotice();
    }
    else if (extra == null && result.isOverThreshold()) {
      this.panel.showThresholdNotice(message.toString());
    }
    else {
      this.panel.showFallbackNotice(message.toString());
    }
  }

  private static String capitalise(String note) {
    if (note == null || note.isEmpty()) {
      return "";
    }
    return Character.toUpperCase(note.charAt(0)) + note.substring(1) + ".";
  }

  private static String sizeHint(int length) {
    return "(" + length / 1024 + " KB)";
  }

  private static void onEdt(Runnable task) {
    if (SwingUtilities.isEventDispatchThread()) {
      task.run();
    } else {
      SwingUtilities.invokeLater(task);
    }
  }

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public String getCaptionName() {
    return this.captionName;
  }

  @Override
  public String getTargetViewName() {
    return "";
  }

  @Override
  public int getPosition() {
    return 2;
  }

  @Override
  public JComponent getPane() {
    return this.panel;
  }

  @Override
  public void setSelected(boolean selected) {
    if (selected) {
      this.panel.getTextArea().requestFocusInWindow();
    }
  }

  @Override
  public void save() {
    String current = this.panel.getTextArea().getText();
    if (!current.equals(this.lastFormatted)) {
      this.writeBackToMessage(current);
    }
    this.lastFormatted = current;
  }

  private boolean writeBackToMessage(String current) {
    String formatted = this.split == null ? current : this.split.toOriginal(current);
    return PrettyWriteBack.apply(this.model.getMessage(), this.request, formatted);
  }

  @Override
  public HttpPanelViewModel getModel() {
    return this.model;
  }

  @Override
  public boolean isEnabled(Message message) {
    return message != null;
  }

  @Override
  public boolean hasChanged() {
    if (this.loading) {
      return false;
    }
    return !this.lastFormatted.equals(this.panel.getTextArea().getText());
  }

  @Override
  public boolean isEditable() {
    return true;
  }

  @Override
  public void setEditable(boolean editable) {
    // stays editable whatever the framework asks for, because the view is meant to be edited and saved
    this.panel.setEditable(true);
  }

  @Override
  public void setParentConfigurationKey(String parentConfigurationKey) {
    this.parentConfigurationKey = parentConfigurationKey;
  }

  public String getParentConfigurationKey() {
    return this.parentConfigurationKey;
  }

  @Override
  public void loadConfiguration(FileConfiguration configuration) {
  }

  @Override
  public void saveConfiguration(FileConfiguration configuration) {
  }

  public void dispose() {
    this.generation.incrementAndGet();
    this.worker.shutdown();
  }

  public int getSubmittedTaskCount() {
    return this.worker.getSubmittedCount();
  }

  public boolean isRequest() {
    return this.request;
  }
}


package org.zaproxy.zap.extension.prettyview.ui;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.awt.*;
import org.apache.commons.configuration.FileConfiguration;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.view.View;
import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelView;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModelEvent;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModelListener;
import org.zaproxy.zap.extension.httppanel.view.text.HttpPanelTextView;
import org.zaproxy.zap.extension.prettyview.async.PrettyWorker;
import org.zaproxy.zap.extension.prettyview.detect.MessageSplitter;
import org.zaproxy.zap.extension.prettyview.detect.PayloadFormat;
import org.zaproxy.zap.extension.prettyview.formatters.PrettyResult;
import org.zaproxy.zap.extension.prettyview.formatters.UniversalPrettifierManager;
import org.zaproxy.zap.extension.search.SearchMatch;
import org.zaproxy.zap.extension.search.SearchMatch.Location;
import org.zaproxy.zap.extension.search.SearchableHttpPanelView;
import org.zaproxy.zap.view.messagecontainer.http.DefaultSingleHttpMessageContainer;

public class CustomPrettyView
implements HttpPanelView,
HttpPanelViewModelListener,
SearchableHttpPanelView {
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
  /** Whether the host panel allows editing; mirrors the core request/response panels. */
  private boolean editable;
  /** The detected format of the loaded message; form posts take a dedicated save path. */
  private PayloadFormat format;
  private String parentConfigurationKey;
  private String lastFormatted = "";
  private DisplayLineSplitter.Split split;
  /**
   * Length of the header region in formatted coordinates. The worker formats the same raw string
   * this is measured from, so the formatted headers are an exact prefix of it.
   */
  private int headerLength;
  /**
   * Whether the loaded message carries no body. A bodyless combined view shows only headers, so
   * it edits like a header view even when the detector reports {@code PLAIN_TEXT}.
   */
  private boolean bodyEmpty;
  /**
   * Whether this view is hosted by a split (Header/Body) component, which hands it the body alone
   * rather than the whole message. Such a view claims no header region and writes back only the
   * body, or the header/body split would blank the request line.
   */
  private final boolean bodyOnly;
  /**
   * Whether this view is hosted by a split component's header pane, which hands it the headers
   * alone. Header text is verbatim wire content and the save parses it strictly (a bad edit leaves
   * the message untouched, exactly like the core header text views), so it is always editable.
   */
  private final boolean headerOnly;

  public CustomPrettyView(String name, String captionName, boolean request) {
    this(name, captionName, request, false, false);
  }

  public CustomPrettyView(String name, String captionName, boolean request, boolean bodyOnly) {
    this(name, captionName, request, bodyOnly, false);
  }

  public CustomPrettyView(String name, String captionName, boolean request, boolean bodyOnly, boolean headerOnly) {
    this.name = Objects.requireNonNull(name, "name");
    this.captionName = Objects.requireNonNull(captionName, "captionName");
    this.request = request;
    this.bodyOnly = bodyOnly;
    this.headerOnly = headerOnly;
    // Editability follows the host panel like every core view: the main viewer (non-editable
    // host) stays read-only while the Manual Request Editor and Requester (editable hosts)
    // stay editable. No forcing here; HttpPanel pushes its flag via setEditable on creation.
    this.editable = false;
    this.prettifierManager = new UniversalPrettifierManager();
    this.worker = new PrettyWorker();
    this.model = new PrettyViewModel(request, this.bodyOnly, this.headerOnly);
    this.textArea = this.createTextArea();
    this.panel = new PrettyViewPanel(this.textArea);
    this.panel.setMessageActions(this::hasHttpMessage, this::showCoreMessageMenu);
    this.model.addHttpPanelViewModelListener(this);
  }

  private boolean hasHttpMessage() {
    return this.model.getMessage() instanceof HttpMessage;
  }

  /**
   * Opens ZAP's own message popup (Attack and friends) at the right-click point.
   *
   * <p>Mirrors what the core text views do: the click-point component carries a single-message
   * container under the same container name the core views use, so message actions enable exactly
   * as they do next door.
   */
  private void showCoreMessageMenu(int x, int y) {
    Message message = this.model.getMessage();
    if (!(message instanceof HttpMessage)) {
      return;
    }
    View view;
    try {
      view = View.getSingleton();
    } catch (RuntimeException e) {
      return;
    }
    if (view == null) {
      return;
    }
    DefaultSingleHttpMessageContainer container = new DefaultSingleHttpMessageContainer(
        HttpPanelTextView.DEFAULT_MESSAGE_CONTAINER_NAME,
        this.panel.getTextArea(),
        (HttpMessage) message);
    view.getPopupMenu().show(container, x, y);
  }

  private RSyntaxTextArea createTextArea() {
    RSyntaxTextArea area = new PrettyTextArea();
    if (area.getDocument() instanceof RSyntaxDocument) {
      ((RSyntaxDocument) area.getDocument())
          .setTokenMakerFactory(new ContinuedStringTokenMakerFactory(TokenMakerFactory.getDefaultInstance()));
    }
    LargePayloadPolicy.applyBaseline(area);
    area.setEditable(false);
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
      this.headerLength = 0;
      this.applyEmpty(ticket);
      return;
    }
    this.panel.showLoading(CustomPrettyView.sizeHint(raw.length()));
    Consumer<PrettyResult> onResult = result -> this.applyOnEdt(ticket, (PrettyResult)result);
    if (this.bodyOnly) {
      this.headerLength = 0;
      this.bodyEmpty = raw.isEmpty();
    } else {
      MessageSplitter.Parts parts = MessageSplitter.split(raw);
      this.headerLength = parts.getHeaders().length();
      // A bodyless message (the common GET) is effectively a header editor: the text is verbatim
      // wire content saved through the strict header parse, so it is safe to edit even though the
      // detector can only report PLAIN_TEXT for it.
      this.bodyEmpty = parts.getBody().isEmpty();
    }
    this.worker.submit(raw, contentType, this.bodyOnly, this.prettifierManager, onResult);
  }

  private void applyEmpty(int ticket) {
    CustomPrettyView.onEdt(() -> {
      if (ticket != this.generation.get()) {
        return;
      }
      this.panel.clearNotice();
      this.headerLength = 0;
      this.bodyEmpty = true;
      this.applyIfCurrent(ticket, PrettyResult.formatted("", null),
          DisplayLineSplitter.split(""),
          ChunkedTextLoader.buildDocument("", "text/plain", () -> ticket == this.generation.get()));
    });
  }

  private void applyOnEdt(int ticket, PrettyResult result) {
    if (SwingUtilities.isEventDispatchThread()) {
      DisplayLineSplitter.Split split = DisplayLineSplitter.split(result.getText());
      this.applyIfCurrent(ticket, result, split,
          ChunkedTextLoader.buildDocument(split.displayText(),
              LargePayloadPolicy.syntaxStyleFor(split, result.getFormat()),
              () -> ticket == this.generation.get()));
    } else {
      // Splitting a ~5 MB payload scans the whole text, and building the document tokenizes it;
      // both run here on the worker thread so the EDT only installs the result. A stale ticket
      // abandons the build at the next slice boundary, and applyIfCurrent drops whatever is left.
      DisplayLineSplitter.Split split = DisplayLineSplitter.split(result.getText());
      String style = LargePayloadPolicy.syntaxStyleFor(split, result.getFormat());
      RSyntaxDocument document = ChunkedTextLoader.buildDocument(split.displayText(), style,
          () -> ticket == this.generation.get());
      SwingUtilities.invokeLater(() -> this.applyIfCurrent(ticket, result, split, document));
    }
  }

  private void applyIfCurrent(
      int ticket, PrettyResult result, DisplayLineSplitter.Split split, RSyntaxDocument document) {
    if (ticket != this.generation.get() || document == null) {
      return;
    }
    this.format = result.getFormat();
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
    ChunkedTextLoader.installDocument(body, document, this.panel::pinToTop, () -> {
      if (ticket != this.generation.get()) {
        return;
      }
      this.lastFormatted = split.displayText();
      this.loading = false;
      this.panel.setEditable(this.allowsEdits());
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
    // Never persist a half-streamed document: while a chunked load is in flight getText() is a
    // prefix of the message and writing it back would truncate the body.
    if (this.loading) {
      return;
    }
    String current = this.panel.getTextArea().getText();
    if (current.equals(this.lastFormatted)) {
      return;
    }
    // Only an editable view writes back. Requests edit wherever the host allows (the main
    // viewer host is read-only, the Manual Request Editor and Requester hosts are editable);
    // responses follow the host too. Multipart and CSV never write back even when the host
    // is editable: their decorations have no safe trip back to wire bytes.
    if (!this.isEditable()) {
      return;
    }
    // Form posts take a dedicated path: the display is decoded and annotated, so it is strictly
    // re-parsed and re-encoded instead of persisted raw. Anything unparseable (truncation notes,
    // multiline values, free text) refuses the save and the message is left untouched.
    if (this.format == PayloadFormat.FORM_URLENCODED) {
      if (this.model.setFormData(current)) {
        this.lastFormatted = current;
      }
      return;
    }
    // Unwrap the display line breaks first (core's ContentSplitter does the same on the way in),
    // then persist through the model like every core text view (see PrettyViewModel.setData).
    // Every format writes back, including decorated ones (multipart banners, padded CSV cells):
    // what you see is what is sent. Form posts take the dedicated path above instead.
    String formatted = this.split == null ? current : this.split.toOriginal(current);
    this.model.setData(formatted);
    this.lastFormatted = current;
  }

  /**
   * Whether the user may edit the loaded message here. Follows the host panel like every core
   * view: editable in the Manual Request Editor and Requester, read-only in the main viewer.
   * Every format is editable, including decorated ones - what is shown is what gets sent.
   */
  private boolean allowsEdits() {
    return this.isEditable();
  }

  /**
   * Contributes this view's hits to ZAP's global Search tab.
   *
   * <p>The pattern runs over the current display text and each hit is mapped back through the
   * split into formatted coordinates, then classified into the header region or the body region
   * (offsets relative to the region start, as the core views report). Body offsets are relative
   * to the <em>formatted</em> body, because pretty-printing is not invertible in general; they
   * round-trip exactly through {@link #highlight(SearchMatch)} on this view.
   */
  @Override
  public void search(Pattern pattern, List<SearchMatch> matches) {
    DisplayLineSplitter.Split split = this.split;
    if (pattern == null || matches == null || split == null) {
      return;
    }
    String display = this.panel.getTextArea().getText();
    if (display.isEmpty()) {
      return;
    }
    int formattedLength = split.originalText().length();
    int bodyStart = Math.min(this.headerLength + 2, formattedLength);
    Location head = this.request ? Location.REQUEST_HEAD : Location.RESPONSE_HEAD;
    Location body = this.request ? Location.REQUEST_BODY : Location.RESPONSE_BODY;
    Matcher matcher = pattern.matcher(display);
    while (matcher.find()) {
      int start = split.toOriginalOffset(matcher.start());
      int end = split.toOriginalOffset(matcher.end());
      if (start < 0) {
        start = 0;
      }
      if (end > formattedLength) {
        end = formattedLength;
      }
      if (start >= end) {
        continue;
      }
      if (end <= this.headerLength) {
        matches.add(new SearchMatch(head, start, end));
      } else if (start >= bodyStart) {
        matches.add(new SearchMatch(body, start - bodyStart, end - bodyStart));
      } else if (start < this.headerLength) {
        matches.add(new SearchMatch(head, start, this.headerLength));
      }
    }
  }

  /**
   * Shows a global-search hit in the editor by selecting it and scrolling it into view.
   *
   * <p>Region-relative offsets are mapped back to display coordinates through the split, so hits
   * reported by {@link #search(Pattern, List)} land exactly. Matches from other views are ignored.
   */
  @Override
  public void highlight(SearchMatch match) {
    DisplayLineSplitter.Split split = this.split;
    if (match == null || split == null) {
      return;
    }
    Location location = match.getLocation();
    Location head = this.request ? Location.REQUEST_HEAD : Location.RESPONSE_HEAD;
    Location body = this.request ? Location.REQUEST_BODY : Location.RESPONSE_BODY;
    int formattedLength = split.originalText().length();
    int formattedStart;
    int formattedEnd;
    if (location == head) {
      formattedStart = Math.min(match.getStart(), this.headerLength);
      formattedEnd = Math.min(match.getEnd(), this.headerLength);
    } else if (location == body) {
      int bodyStart = Math.min(this.headerLength + 2, formattedLength);
      formattedStart = Math.min(bodyStart + match.getStart(), formattedLength);
      formattedEnd = Math.min(bodyStart + match.getEnd(), formattedLength);
    } else {
      return;
    }
    if (formattedStart >= formattedEnd) {
      return;
    }
    int displayStart = split.toDisplayOffset(formattedStart);
    int displayEnd = split.toDisplayOffset(formattedEnd);
    CustomPrettyView.onEdt(() -> {
      RSyntaxTextArea area = this.panel.getTextArea();
      area.setCaretPosition(displayStart);
      area.moveCaretPosition(displayEnd);
      try {
        // Null when the component has no laid-out view yet; the selection still stands.
        java.awt.geom.Rectangle2D at = area.modelToView2D(displayEnd);
        if (at != null) {
          area.scrollRectToVisible(at.getBounds());
        }
      } catch (BadLocationException e) {
        // The document changed under the highlight; the selection still stands.
      }
    });
  }

  @Override
  public HttpPanelViewModel getModel() {
    return this.model;
  }

  @Override
  public boolean isEnabled(Message message) {
    // Always enabled, even with no message: the view shows the empty tab rather than vanishing
    // from the tab list, which is what keeps Pretty selected when nothing is selected in ZAP.
    return true;
  }

  @Override
  public boolean hasChanged() {
    // Core parity: the core text views always report changed and let the panel save
    // unconditionally; save() itself still skips redundant and disallowed writes.
    return true;
  }

  @Override
  public boolean isEditable() {
    // Follow the host panel like every core view: editable in the Manual Request Editor and
    // Requester, read-only in the main viewer. Falls back to the host flag when setEditable
    // has not been pushed yet.
    if (this.editable) {
      return true;
    }
    java.awt.Container parent = this.panel.getParent();
    while (parent != null) {
      if (parent instanceof org.zaproxy.zap.extension.httppanel.HttpPanel) {
        return ((org.zaproxy.zap.extension.httppanel.HttpPanel) parent).isEditable();
      }
      parent = parent.getParent();
    }
    return this.editable;
  }

  @Override
  public void setEditable(boolean editable) {
    // Follow the host panel like every core view; see isEditable.
    this.editable = editable;
    if (!this.loading) {
      this.panel.setEditable(this.allowsEdits());
    }
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

  /**
   * Releases this view's resources. ZAP never calls this itself — {@code HttpPanelView} defines no
   * dispose hook — so the owning factory calls it for every live view when the add-on unloads.
   */
  public void dispose() {
    this.generation.incrementAndGet();
    this.worker.shutdown();
    this.loading = false;
    this.panel.dispose();
  }

  public int getSubmittedTaskCount() {
    return this.worker.getSubmittedCount();
  }

  public boolean isRequest() {
    return this.request;
  }
}


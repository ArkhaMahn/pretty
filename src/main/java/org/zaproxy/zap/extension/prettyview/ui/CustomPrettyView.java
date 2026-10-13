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
import java.awt.Window;
import java.awt.Container;
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
  /**
   * Whether the displayed format round-trips through save/load. Multipart, CSV and plain text
   * render with decorations the wire format has no room for (padded cells, part banners, collapsed
   * whitespace), so persisting the display text would corrupt the message and those views stay
   * read-only; the code formats only move whitespace and case. Form posts bypass this gate through
   * a strict re-encode on save instead (see {@link #allowsEdits}).
   */
  private boolean formatEditable = true;
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
    // Detect if in editor/requester context (Manual Request Editor or Requester)
    boolean inEditor = false;
    // Can't check parent yet as panel not created; but we'll be called from UI - alternatively, rely on setEditable called by host
    
    boolean inEditorInit = false;
    if (this.request && (this.bodyOnly || this.headerOnly)) {
      inEditorInit = true;
    }
    if (request && !inEditorInit) {
      this.editable = false;
    } else {
      this.editable = request;
    }
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
    this.formatEditable = this.headerOnly || this.bodyEmpty || CustomPrettyView.isWireSafe(result.getFormat());
    this.format = result.getFormat();
    if (!this.allowsEdits() && !this.formatEditable) {
      this.textArea.setToolTipText(
          "Decorated rendering \u2014 read-only; edit the message in the Source tab.");
    } else {
      this.textArea.setToolTipText(null);
    }
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
    // Only an editable view writes back. Responses stay read-only everywhere; requests edit
    // wherever the host allows. Multipart, CSV and plain text never write back even when the host
    // is editable: their decorations have no safe trip back to wire bytes.
    if (!this.editable) {
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
    if (!this.formatEditable) {
      return;
    }
    // Unwrap the display line breaks first (core's ContentSplitter does the same on the way in),
    // then persist through the model like every core text view (see PrettyViewModel.setData).
    String formatted = this.split == null ? current : this.split.toOriginal(current);
    this.model.setData(formatted);
    this.lastFormatted = current;
  }

  /**
   * Whether the user may edit the loaded message here. Wire-safe formats, header views and
   * bodyless messages edit directly; form posts edit through a strict re-encode on save that
   * refuses anything it cannot parse back exactly. Everything else stays display-only.
   */
  private boolean allowsEdits() {
    return this.editable && (this.formatEditable || this.format == PayloadFormat.FORM_URLENCODED);
  }

  /**
   * Whether a formatted body survives a save semantically intact. The formats below only move
   * whitespace and letter case (or re-spell entities, in HTML's case) - everything a parser reads
   * back identically. Everything else renders decorations - padded CSV cells, "a = 1" pairs, part
   * banners, collapsed runs - that change what the bytes mean, so those views are display-only.
   */
  private static boolean isWireSafe(PayloadFormat format) {
    if (format == null) {
      return true;
    }
    switch (format) {
      case JSON:
      case GRAPHQL:
      case HTML:
      case XML:
      case CSS:
      case JAVASCRIPT:
      case SQL:
        return true;
      default:
        return false;
    }
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
    // Check editor context
    boolean inEditor = false;
    // Split parts are typically used by editors (MRE/Requester)
    if (this.request && (this.bodyOnly || this.headerOnly)) {
      inEditor = true;
    }
    java.awt.Container check = this.panel.getParent();
    while (check != null) {
      String cname = check.getClass().getName();
      String name = check.getName();
      if (cname.contains("Manual") || cname.contains("manual") || (cname.contains("Request") && (cname.contains("Editor") || cname.contains("er"))) || cname.contains("requester") || cname.contains("Requester") || cname.contains("MessageEditor") || cname.contains("ManualHttp") || cname.contains("HttpRequestEditor") || cname.contains("Resend") || cname.contains("addon.requester") || cname.contains("org.zaproxy.addon.requester") || cname.contains("MessageEditorDialog") || cname.contains("RequesterPanel") || cname.contains("requester-panel") || cname.contains("CustomHttpPanelRequest") || cname.contains("HttpPanelSender") || (name != null && (name.toLowerCase().contains("requester") || name.toLowerCase().contains("manual") || name.toLowerCase().contains("editor")))) {
        inEditor = true;
        break;
      }
      if (check instanceof java.awt.Window) {
        // also keep window check
      }
      check = check.getParent();
    }
    if (this.request && !inEditor) {
      return false;
    }
    if (this.editable) {
      return true;
    }
    java.awt.Container parent = this.panel.getParent();
    while (parent != null) {
      if (parent instanceof org.zaproxy.zap.extension.httppanel.HttpPanel) {
        boolean hostEditable = ((org.zaproxy.zap.extension.httppanel.HttpPanel) parent).isEditable();
        // Also allow in editor windows
        java.awt.Container pp = parent;
        while (pp != null) {
          if (pp instanceof java.awt.Window) {
            java.awt.Window w = (java.awt.Window) pp;
            if (w.getClass().getName().contains("ManualRequest") || w.getClass().getName().contains("Requester")) {
              return true;
            }
          }
          pp = pp.getParent();
        }
        return hostEditable;
      }
      parent = parent.getParent();
    }
    return this.editable;
  }

  @Override
  public void setEditable(boolean editable) {
    // Request views stay editable everywhere. Only the send flows persist (Requester and the
    // Manual Request Editor call saveData on send; nothing ever saves the main window panels),
    // so an editable main-window view is harmless while every editor panel writes back on send.
    // Response views keep following the host panel, like every core view.
    // Allow in editor windows
    boolean inEditor = false;
    java.awt.Container pp = this.panel.getParent();
    while (pp != null) {
      String cname = pp.getClass().getName();
      String name = pp.getName();
      if (cname.contains("Manual") || cname.contains("manual") || (cname.contains("Request") && (cname.contains("Editor") || cname.contains("er"))) || cname.contains("requester") || cname.contains("Requester") || cname.contains("MessageEditor") || cname.contains("ManualHttp") || cname.contains("HttpRequestEditor") || cname.contains("Resend") || cname.contains("addon.requester") || cname.contains("org.zaproxy.addon.requester") || cname.contains("MessageEditorDialog") || cname.contains("RequesterPanel") || cname.contains("CustomHttpPanelRequest") || cname.contains("RequesterNumbered") || cname.contains("AbstractHttpMessageEditorDialog") || (name != null && (name.toLowerCase().contains("requester") || name.toLowerCase().contains("manual") || name.toLowerCase().contains("editor")))) {
        inEditor = true;
        break;
      }
      pp = pp.getParent();
    }
    // Only make request viewer read-only in non-editor contexts (main viewer)
    if (!inEditor && this.request) {
      editable = false;
    } else if (inEditor && this.request) {
      // In editors, keep request editable as before
      if (!editable) {
        editable = true;
      }
    }
    // Response views follow host as before (not modified)
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


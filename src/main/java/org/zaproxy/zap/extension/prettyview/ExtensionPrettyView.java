package org.zaproxy.zap.extension.prettyview;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.parosproxy.paros.view.View;
import org.zaproxy.zap.extension.httppanel.ComponentChangedEvent;
import org.zaproxy.zap.extension.httppanel.HttpPanel;
import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.extension.httppanel.MessagePanelEventListener;
import org.zaproxy.zap.extension.httppanel.MessageViewSelectedEvent;
import org.zaproxy.zap.extension.httppanel.component.split.request.RequestSplitComponent;
import org.zaproxy.zap.extension.httppanel.component.split.response.ResponseSplitComponent;
import org.zaproxy.zap.extension.prettyview.formatters.PrettifyThreshold;
import org.zaproxy.zap.extension.prettyview.ui.PrettyTextPopupItem;
import org.zaproxy.zap.extension.prettyview.ui.PrettyViewFactory;
import org.zaproxy.zap.extension.prettyview.view.PrettyDefaultViewSelectorFactory;
import org.zaproxy.zap.extension.prettyview.view.ViewChoiceMemory;
import org.zaproxy.zap.extension.prettyview.view.ViewChoiceTracker;
import org.zaproxy.zap.view.HttpPanelManager;

public class ExtensionPrettyView
extends ExtensionAdaptor {
  private static final Logger LOGGER = LogManager.getLogger(ExtensionPrettyView.class);
  public static final String REQUEST_VIEW_NAME = "prettyview.request";
  public static final String RESPONSE_VIEW_NAME = "prettyview.response";
  public static final String CAPTION_NAME = "Pretty";
  public static final String REQUEST_SELECTOR_NAME = "prettyview.request.defaultViewSelector";
  public static final String RESPONSE_SELECTOR_NAME = "prettyview.response.defaultViewSelector";
  private PrettyViewFactory requestFactory;
  private PrettyViewFactory responseFactory;
  private PrettyViewFactory requestSplitFactory;
  private PrettyViewFactory responseSplitFactory;
  private PrettyViewFactory requestSplitHeaderFactory;
  private PrettyViewFactory responseSplitHeaderFactory;
  private final ViewChoiceMemory choices = new ViewChoiceMemory();
  private MessagePanelEventListener viewChoiceListener;
  private HttpPanel requestPanel;
  private HttpPanel responsePanel;

  public ExtensionPrettyView() {
    super("Pretty View");
  }

  @Override
  public void hook(ExtensionHook extensionHook) {
    super.hook(extensionHook);
    HttpPanelManager manager = HttpPanelManager.getInstance();
    this.requestFactory = new PrettyViewFactory(REQUEST_VIEW_NAME, CAPTION_NAME, true);
    manager.addRequestViewFactory("RequestAll", this.requestFactory);
    this.responseFactory = new PrettyViewFactory(RESPONSE_VIEW_NAME, CAPTION_NAME, false);
    manager.addResponseViewFactory("ResponseAll", this.responseFactory);
    // The Manual Request Editor (open/resend) and the Requester add-on show the split
    // (Header/Body) component, so the same view is offered on its Body pane. Editability is
    // not set here: like every core view, this view takes it from the host panel through
    // HttpPanel.addComponent/addView, which push the panel's flag into each view on creation.
    this.requestSplitFactory = new PrettyViewFactory(REQUEST_VIEW_NAME, CAPTION_NAME, true, true);
    manager.addRequestViewFactory("RequestSplit", this.requestSplitFactory);
    this.responseSplitFactory = new PrettyViewFactory(RESPONSE_VIEW_NAME, CAPTION_NAME, false, true);
    manager.addResponseViewFactory("ResponseSplit", this.responseSplitFactory);
    // The split Header panes get the same view, scoped to the headers: header text is verbatim
    // wire content, so it is safe to edit (a bad edit simply fails the strict header parse and
    // the message is left untouched, exactly like the core header text views).
    this.requestSplitHeaderFactory = new PrettyViewFactory(REQUEST_VIEW_NAME, CAPTION_NAME, true, false, true);
    manager.addRequestViewFactory("RequestSplit", this.requestSplitHeaderFactory);
    this.responseSplitHeaderFactory = new PrettyViewFactory(RESPONSE_VIEW_NAME, CAPTION_NAME, false, false, true);
    manager.addResponseViewFactory("ResponseSplit", this.responseSplitHeaderFactory);
    int maxPrettifyChars = PrettifyThreshold.defaultThreshold().getThresholdChars();
    manager.addRequestDefaultViewSelectorFactory("RequestAll", new PrettyDefaultViewSelectorFactory(REQUEST_SELECTOR_NAME, REQUEST_VIEW_NAME, true, maxPrettifyChars, this.choices));
    manager.addResponseDefaultViewSelectorFactory("ResponseAll", new PrettyDefaultViewSelectorFactory(RESPONSE_SELECTOR_NAME, RESPONSE_VIEW_NAME, false, maxPrettifyChars, this.choices));
    // The Manual Request Editor (open/resend) and the Requester add-on both host the split
    // (Header/Body) component, so the body pane defaults to Pretty there too - editable, exactly
    // as on the main panels. Past the prettify threshold the selector stands down and ZAP's own
    // large body view takes over, and a stood-down choice is honoured here as well.
    manager.addRequestDefaultViewSelectorFactory("RequestSplit", new PrettyDefaultViewSelectorFactory(REQUEST_SELECTOR_NAME, REQUEST_VIEW_NAME, true, maxPrettifyChars, this.choices, RequestSplitComponent.ViewComponent.BODY));
    manager.addResponseDefaultViewSelectorFactory("ResponseSplit", new PrettyDefaultViewSelectorFactory(RESPONSE_SELECTOR_NAME, RESPONSE_VIEW_NAME, false, maxPrettifyChars, this.choices, RequestSplitComponent.ViewComponent.BODY));
    // Text operations ride ZAP's own message popup at core's text weights, so Pretty's menu
    // orders exactly like the core Text view. Each entry only shows inside a Pretty editor and
    // acts directly on it, never through focus state.
    for (PrettyTextPopupItem.Kind kind : PrettyTextPopupItem.Kind.values()) {
      extensionHook.getHookMenu().addPopupMenuItem(new PrettyTextPopupItem(kind));
    }
    extensionHook.getHookMenu().addPopupMenuItem(
        new org.zaproxy.zap.extension.prettyview.ui.PrettyViewPopupItem());
  }

  /**
   * Watches tab switches on the main request and response panels so a manually chosen tab sticks.
   *
   * <p>ZAP re-selects the default view on every message, which would drag the tab back to Pretty
   * on every click after the user picked another view. The tracker records genuine choices (and
   * only those - see {@link ViewChoiceTracker}) and the selectors stand down while one stands.
   */
  @Override
  public void postInit() {
    super.postInit();
    if (!hasView()) {
      return;
    }
    View view;
    try {
      view = View.getSingleton();
    } catch (RuntimeException e) {
      LOGGER.warn("No view available; tab choices will not be tracked.", e);
      return;
    }
    if (view == null) {
      return;
    }
    HttpPanel requestPanel = view.getRequestPanel();
    HttpPanel responsePanel = view.getResponsePanel();
    if (requestPanel == null || responsePanel == null) {
      return;
    }
    ViewChoiceTracker tracker = new ViewChoiceTracker(
        requestPanel,
        responsePanel,
        this.choices,
        PrettifyThreshold.defaultThreshold().getThresholdChars());
    this.viewChoiceListener = new MessagePanelEventListener() {
      @Override
      public void viewSelected(MessageViewSelectedEvent event) {
        Message message = null;
        Object source = event.getSource();
        try {
          if (source instanceof HttpPanel) {
            message = ((HttpPanel) source).getMessage();
          }
        } catch (RuntimeException e) {
          LOGGER.debug("Could not read the showing message for a tab switch.", e);
        }
        tracker.viewSelected(
            source, event.getPreviousView(), event.getCurrentView(), message);
      }

      @Override
      public void componentChanged(ComponentChangedEvent event) {
        // Component switches (All versus split) carry no tab choice; nothing to record.
      }
    };
    requestPanel.addMessagePanelEventListener(this.viewChoiceListener);
    responsePanel.addMessagePanelEventListener(this.viewChoiceListener);
    this.requestPanel = requestPanel;
    this.responsePanel = responsePanel;
  }

  @Override
  public boolean canUnload() {
    return true;
  }

  @Override
  public void unload() {
    // Workers and timers live on the views, not the factories, so release them first: after this
    // no in-flight task can write into a detached panel.
    if (this.requestFactory != null) {
      this.requestFactory.disposeAll();
    }
    if (this.responseFactory != null) {
      this.responseFactory.disposeAll();
    }
    if (this.requestSplitFactory != null) {
      this.requestSplitFactory.disposeAll();
    }
    if (this.responseSplitFactory != null) {
      this.responseSplitFactory.disposeAll();
    }
    if (this.requestSplitHeaderFactory != null) {
      this.requestSplitHeaderFactory.disposeAll();
    }
    if (this.responseSplitHeaderFactory != null) {
      this.responseSplitHeaderFactory.disposeAll();
    }
    HttpPanelManager manager = HttpPanelManager.getInstance();
    // Each removal runs even if an earlier one throws, so one failure cannot leave ZAP
    // half-unloaded; failures are logged and unloading continues.
    ExtensionPrettyView.remove(() -> manager.removeRequestDefaultViewSelectorFactory("RequestAll", REQUEST_SELECTOR_NAME), "request default view selector factory");
    ExtensionPrettyView.remove(() -> manager.removeRequestDefaultViewSelectors("RequestAll", REQUEST_SELECTOR_NAME, null), "request default view selectors");
    ExtensionPrettyView.remove(() -> manager.removeResponseDefaultViewSelectorFactory("ResponseAll", RESPONSE_SELECTOR_NAME), "response default view selector factory");
    ExtensionPrettyView.remove(() -> manager.removeResponseDefaultViewSelectors("ResponseAll", RESPONSE_SELECTOR_NAME, null), "response default view selectors");
    ExtensionPrettyView.remove(() -> manager.removeRequestDefaultViewSelectorFactory("RequestSplit", REQUEST_SELECTOR_NAME), "split request default view selector factory");
    ExtensionPrettyView.remove(() -> manager.removeRequestDefaultViewSelectors("RequestSplit", REQUEST_SELECTOR_NAME, RequestSplitComponent.ViewComponent.BODY), "split request default view selectors");
    ExtensionPrettyView.remove(() -> manager.removeResponseDefaultViewSelectorFactory("ResponseSplit", RESPONSE_SELECTOR_NAME), "split response default view selector factory");
    ExtensionPrettyView.remove(() -> manager.removeResponseDefaultViewSelectors("ResponseSplit", RESPONSE_SELECTOR_NAME, ResponseSplitComponent.ViewComponent.BODY), "split response default view selectors");
    ExtensionPrettyView.remove(() -> manager.removeRequestViewFactory("RequestAll", REQUEST_VIEW_NAME), "request view factory");
    ExtensionPrettyView.remove(() -> manager.removeRequestViews("RequestAll", REQUEST_VIEW_NAME, null), "request views");
    ExtensionPrettyView.remove(() -> manager.removeResponseViewFactory("ResponseAll", RESPONSE_VIEW_NAME), "response view factory");
    ExtensionPrettyView.remove(() -> manager.removeResponseViews("ResponseAll", RESPONSE_VIEW_NAME, null), "response views");
    ExtensionPrettyView.remove(() -> manager.removeRequestViewFactory("RequestSplit", PrettyViewFactory.factoryNameFor(REQUEST_VIEW_NAME, true, false)), "split request view factory");
    ExtensionPrettyView.remove(() -> manager.removeRequestViews("RequestSplit", REQUEST_VIEW_NAME, RequestSplitComponent.ViewComponent.BODY), "split request views");
    ExtensionPrettyView.remove(() -> manager.removeResponseViewFactory("ResponseSplit", PrettyViewFactory.factoryNameFor(RESPONSE_VIEW_NAME, true, false)), "split response view factory");
    ExtensionPrettyView.remove(() -> manager.removeResponseViews("ResponseSplit", RESPONSE_VIEW_NAME, ResponseSplitComponent.ViewComponent.BODY), "split response views");
    ExtensionPrettyView.remove(() -> manager.removeRequestViewFactory("RequestSplit", PrettyViewFactory.factoryNameFor(REQUEST_VIEW_NAME, false, true)), "split request header view factory");
    ExtensionPrettyView.remove(() -> manager.removeRequestViews("RequestSplit", REQUEST_VIEW_NAME, RequestSplitComponent.ViewComponent.HEADER), "split request header views");
    ExtensionPrettyView.remove(() -> manager.removeResponseViewFactory("ResponseSplit", PrettyViewFactory.factoryNameFor(RESPONSE_VIEW_NAME, false, true)), "split response header view factory");
    ExtensionPrettyView.remove(() -> manager.removeResponseViews("ResponseSplit", RESPONSE_VIEW_NAME, ResponseSplitComponent.ViewComponent.HEADER), "split response header views");
    if (this.viewChoiceListener != null) {
      if (this.requestPanel != null) {
        try {
          this.requestPanel.removeMessagePanelEventListener(this.viewChoiceListener);
        } catch (RuntimeException e) {
          LOGGER.warn("Failed to detach the request tab tracker during unload.", e);
        }
      }
      if (this.responsePanel != null) {
        try {
          this.responsePanel.removeMessagePanelEventListener(this.viewChoiceListener);
        } catch (RuntimeException e) {
          LOGGER.warn("Failed to detach the response tab tracker during unload.", e);
        }
      }
      this.viewChoiceListener = null;
      this.requestPanel = null;
      this.responsePanel = null;
    }
  }

  private static void remove(Runnable removal, String what) {
    try {
      removal.run();
    } catch (RuntimeException e) {
      LOGGER.warn("Failed to remove {} during unload; continuing.", what, e);
    }
  }

  @Override
  public String getUIName() {
    return "Pretty View";
  }

  @Override
  public String getDescription() {
    return "Adds a Pretty tab to the HTTP Request and HTTP Response panels that auto-detects and re-formats JSON, HTML, XML, CSS, JavaScript, GraphQL, form data, multipart, SQL and CSV payloads, and word-wraps the result so long lines never overlap.";
  }

  @Override
  public String getAuthor() {
    return "ZAProxy Lab";
  }

  @Override
  public boolean supportsDb(String s) {
    return true;
  }
}


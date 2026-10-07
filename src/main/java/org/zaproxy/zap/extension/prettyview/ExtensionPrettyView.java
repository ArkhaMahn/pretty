package org.zaproxy.zap.extension.prettyview;

import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.zaproxy.zap.extension.prettyview.ui.PrettyViewFactory;
import org.zaproxy.zap.extension.prettyview.view.PrettyDefaultViewSelectorFactory;
import org.zaproxy.zap.view.HttpPanelManager;

public class ExtensionPrettyView
extends ExtensionAdaptor {
  public static final String REQUEST_VIEW_NAME = "prettyview.request";
  public static final String RESPONSE_VIEW_NAME = "prettyview.response";
  public static final String CAPTION_NAME = "Pretty";
  public static final String REQUEST_SELECTOR_NAME = "prettyview.request.defaultViewSelector";
  public static final String RESPONSE_SELECTOR_NAME = "prettyview.response.defaultViewSelector";

  public ExtensionPrettyView() {
    super("Pretty View");
  }

  @Override
  public void hook(ExtensionHook extensionHook) {
    super.hook(extensionHook);
    HttpPanelManager manager = HttpPanelManager.getInstance();
    PrettyViewFactory requestFactory = new PrettyViewFactory(REQUEST_VIEW_NAME, CAPTION_NAME, true);
    manager.addRequestViewFactory("RequestAll", requestFactory);
    PrettyViewFactory responseFactory = new PrettyViewFactory(RESPONSE_VIEW_NAME, CAPTION_NAME, false);
    manager.addResponseViewFactory("ResponseAll", responseFactory);
    manager.addRequestDefaultViewSelectorFactory("RequestAll", new PrettyDefaultViewSelectorFactory(REQUEST_SELECTOR_NAME, REQUEST_VIEW_NAME, true));
    manager.addResponseDefaultViewSelectorFactory("ResponseAll", new PrettyDefaultViewSelectorFactory(RESPONSE_SELECTOR_NAME, RESPONSE_VIEW_NAME, false));
  }

  @Override
  public boolean canUnload() {
    return true;
  }

  @Override
  public void unload() {
    HttpPanelManager manager = HttpPanelManager.getInstance();
    manager.removeRequestDefaultViewSelectorFactory("RequestAll", REQUEST_SELECTOR_NAME);
    manager.removeRequestDefaultViewSelectors("RequestAll", REQUEST_SELECTOR_NAME, null);
    manager.removeResponseDefaultViewSelectorFactory("ResponseAll", RESPONSE_SELECTOR_NAME);
    manager.removeResponseDefaultViewSelectors("ResponseAll", RESPONSE_SELECTOR_NAME, null);
    manager.removeRequestViewFactory("RequestAll", REQUEST_VIEW_NAME);
    manager.removeRequestViews("RequestAll", REQUEST_VIEW_NAME, null);
    manager.removeResponseViewFactory("ResponseAll", RESPONSE_VIEW_NAME);
    manager.removeResponseViews("ResponseAll", RESPONSE_VIEW_NAME, null);
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


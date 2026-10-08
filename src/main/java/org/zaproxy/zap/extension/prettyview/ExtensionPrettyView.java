package org.zaproxy.zap.extension.prettyview;

import java.awt.Component;
import java.awt.Container;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.swing.JPopupMenu;
import javax.swing.text.JTextComponent;

import org.parosproxy.paros.extension.AbstractPanel;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.parosproxy.paros.view.View;
import org.parosproxy.paros.view.WorkbenchPanel;
import org.zaproxy.zap.extension.httppanel.view.text.HttpPanelTextArea;
import org.zaproxy.zap.extension.prettyview.ui.PrettyViewFactory;
import org.zaproxy.zap.extension.prettyview.ui.TextContextMenu;
import org.zaproxy.zap.extension.prettyview.view.PrettyDefaultViewSelectorFactory;
import org.zaproxy.zap.extension.search.SearchPanel;
import org.zaproxy.zap.view.HttpPanelManager;

public class ExtensionPrettyView
extends ExtensionAdaptor {
  private final Map<JTextComponent, JPopupMenu> replacedPopupMenus = new IdentityHashMap<>();
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

  /**
   * Gives ZAP's own request and response panels the same right-click menu as the Pretty view.
   *
   * <p>The core text views carry only ZAP's message menu (Open, Resend and so on), with no Cut, Copy,
   * Paste or Find. The Pretty menu is put in their place, and Find opens ZAP's Search tab, so the
   * clipboard and search entries sit where the other panels already look for them. The text areas are
   * found by walking the live request and response panels, and the menu each one had is remembered so
   * unloading the add-on restores it.
   */
  @Override
  public void postInit() {
    super.postInit();
    View view = View.getSingleton();
    if (view == null) {
      return;
    }
    this.installCoreContextMenus(view.getRequestPanel());
    this.installCoreContextMenus(view.getResponsePanel());
  }

  private void installCoreContextMenus(Component root) {
    if (root == null) {
      return;
    }
    if (root instanceof HttpPanelTextArea) {
      HttpPanelTextArea area = (HttpPanelTextArea) root;
      if (!this.replacedPopupMenus.containsKey(area)) {
        this.replacedPopupMenus.put(area, area.getComponentPopupMenu());
        area.setComponentPopupMenu(new TextContextMenu(this::focusZapSearch, null, null));
      }
    }
    if (root instanceof Container) {
      for (Component child : ((Container) root).getComponents()) {
        this.installCoreContextMenus(child);
      }
    }
  }

  private void focusZapSearch() {
    View view = View.getSingleton();
    if (view == null) {
      return;
    }
    WorkbenchPanel workbench = view.getWorkbench();
    for (WorkbenchPanel.PanelType type : WorkbenchPanel.PanelType.values()) {
      for (AbstractPanel panel : workbench.getPanels(type)) {
        if (panel instanceof SearchPanel) {
          ((SearchPanel) panel).searchFocus();
          return;
        }
      }
    }
  }

  @Override
  public boolean canUnload() {
    return true;
  }

  @Override
  public void unload() {
    for (Map.Entry<JTextComponent, JPopupMenu> entry : this.replacedPopupMenus.entrySet()) {
      entry.getKey().setComponentPopupMenu(entry.getValue());
    }
    this.replacedPopupMenus.clear();
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


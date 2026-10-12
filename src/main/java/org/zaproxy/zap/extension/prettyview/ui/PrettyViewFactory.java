package org.zaproxy.zap.extension.prettyview.ui;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.zaproxy.zap.extension.httppanel.component.split.request.RequestSplitComponent;
import org.zaproxy.zap.extension.httppanel.component.split.response.ResponseSplitComponent;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelView;
import org.zaproxy.zap.view.HttpPanelManager;

public class PrettyViewFactory
implements HttpPanelManager.HttpPanelViewFactory {
  private final String name;
  private final String captionName;
  private final boolean request;
  /**
   * Whether this factory serves a split (Header/Body) component. Those hand a view only their own
   * part and route it by the {@code ViewComponent} returned from {@link #getOptions()}.
   */
  private final boolean bodyOnly;
  private final boolean headerOnly;
  /**
   * Every view this factory handed out. ZAP's {@code HttpPanelView} has no dispose hook, so the
   * extension releases workers and timers through here on unload instead.
   */
  private final Set<CustomPrettyView> views =
      Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

  public PrettyViewFactory(String name, String captionName, boolean request) {
    this(name, captionName, request, false, false);
  }

  public PrettyViewFactory(String name, String captionName, boolean request, boolean bodyOnly) {
    this(name, captionName, request, bodyOnly, false);
  }

  public PrettyViewFactory(String name, String captionName, boolean request, boolean bodyOnly, boolean headerOnly) {
    this.name = name;
    this.captionName = captionName;
    this.request = request;
    this.bodyOnly = bodyOnly;
    this.headerOnly = headerOnly;
  }

  /**
   * Factories share one component map per component name, keyed by this, so the header and body
   * registrations under the same split component need distinct names. The views themselves keep
   * the plain view name: header and body panes hold separate view maps.
   */
  public static String factoryNameFor(String viewName, boolean bodyOnly, boolean headerOnly) {
    if (bodyOnly) {
      return viewName + ".body";
    }
    if (headerOnly) {
      return viewName + ".header";
    }
    return viewName;
  }

  @Override
  public String getName() {
    return PrettyViewFactory.factoryNameFor(this.name, this.bodyOnly, this.headerOnly);
  }

  @Override
  public HttpPanelView getNewView() {
    CustomPrettyView view = new CustomPrettyView(this.name, this.captionName, this.request, this.bodyOnly, this.headerOnly);
    this.views.add(view);
    return view;
  }

  /** Cancels pending work and releases timers for every view this factory created. */
  public void disposeAll() {
    CustomPrettyView[] snapshot;
    synchronized (this.views) {
      snapshot = this.views.toArray(new CustomPrettyView[0]);
      this.views.clear();
    }
    for (CustomPrettyView view : snapshot) {
      view.dispose();
    }
  }

  /**
   * The split components ({@code RequestSplit} / {@code ResponseSplit}) route a view to its header
   * or body pane by comparing this against {@code ViewComponent.HEADER}/{@code BODY}; the combined
   * components ignore it, so they get {@code null}.
   */
  @Override
  public Object getOptions() {
    if (this.bodyOnly) {
      return this.request
          ? RequestSplitComponent.ViewComponent.BODY
          : ResponseSplitComponent.ViewComponent.BODY;
    }
    if (this.headerOnly) {
      return this.request
          ? RequestSplitComponent.ViewComponent.HEADER
          : ResponseSplitComponent.ViewComponent.HEADER;
    }
    return null;
  }
}


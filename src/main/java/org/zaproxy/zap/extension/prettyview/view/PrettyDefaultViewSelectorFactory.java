package org.zaproxy.zap.extension.prettyview.view;

import org.zaproxy.zap.extension.httppanel.view.HttpPanelDefaultViewSelector;
import org.zaproxy.zap.extension.prettyview.formatters.PrettifyThreshold;
import org.zaproxy.zap.view.HttpPanelManager;

public final class PrettyDefaultViewSelectorFactory
implements HttpPanelManager.HttpPanelDefaultViewSelectorFactory {
  private final String name;
  private final String viewName;
  private final boolean request;
  private final int maxPrettifyChars;

  public PrettyDefaultViewSelectorFactory(String name, String viewName, boolean request) {
    this(name, viewName, request, PrettifyThreshold.defaultThreshold().getThresholdChars());
  }

  public PrettyDefaultViewSelectorFactory(
      String name, String viewName, boolean request, int maxPrettifyChars) {
    this.name = name;
    this.viewName = viewName;
    this.request = request;
    this.maxPrettifyChars = maxPrettifyChars;
  }

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public HttpPanelDefaultViewSelector getNewDefaultViewSelector() {
    return new PrettyDefaultViewSelector(this.name, this.viewName, this.request, this.maxPrettifyChars);
  }

  @Override
  public Object getOptions() {
    return null;
  }
}
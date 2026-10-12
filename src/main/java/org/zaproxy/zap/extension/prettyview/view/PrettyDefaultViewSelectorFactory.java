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
  private final ViewChoiceMemory choices;
  private final Object options;

  public PrettyDefaultViewSelectorFactory(String name, String viewName, boolean request) {
    this(name, viewName, request, PrettifyThreshold.defaultThreshold().getThresholdChars(), null, null);
  }

  public PrettyDefaultViewSelectorFactory(
      String name, String viewName, boolean request, int maxPrettifyChars) {
    this(name, viewName, request, maxPrettifyChars, null, null);
  }

  public PrettyDefaultViewSelectorFactory(
      String name,
      String viewName,
      boolean request,
      int maxPrettifyChars,
      ViewChoiceMemory choices) {
    this(name, viewName, request, maxPrettifyChars, choices, null);
  }

  /**
   * @param options routing option passed to the component, e.g. {@link
   *     org.zaproxy.zap.extension.httppanel.component.split.request.RequestSplitComponent.ViewComponent#BODY}
   *     so the split (Header/Body) editor defaults the body pane to Pretty; {@code null} for the
   *     combined message panels.
   */
  public PrettyDefaultViewSelectorFactory(
      String name,
      String viewName,
      boolean request,
      int maxPrettifyChars,
      ViewChoiceMemory choices,
      Object options) {
    this.name = name;
    this.viewName = viewName;
    this.request = request;
    this.maxPrettifyChars = maxPrettifyChars;
    this.choices = choices;
    this.options = options;
  }

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public HttpPanelDefaultViewSelector getNewDefaultViewSelector() {
    return new PrettyDefaultViewSelector(
        this.name, this.viewName, this.request, this.maxPrettifyChars, this.choices);
  }

  @Override
  public Object getOptions() {
    return this.options;
  }
}
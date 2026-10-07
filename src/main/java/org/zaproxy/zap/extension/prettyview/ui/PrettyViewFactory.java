package org.zaproxy.zap.extension.prettyview.ui;

import org.zaproxy.zap.extension.httppanel.view.HttpPanelView;
import org.zaproxy.zap.view.HttpPanelManager;

public class PrettyViewFactory
implements HttpPanelManager.HttpPanelViewFactory {
  private final String name;
  private final String captionName;
  private final boolean request;

  public PrettyViewFactory(String name, String captionName, boolean request) {
    this.name = name;
    this.captionName = captionName;
    this.request = request;
  }

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public HttpPanelView getNewView() {
    return new CustomPrettyView(this.name, this.captionName, this.request);
  }

  @Override
  public Object getOptions() {
    return null;
  }
}


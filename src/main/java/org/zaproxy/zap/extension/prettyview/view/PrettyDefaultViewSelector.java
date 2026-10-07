package org.zaproxy.zap.extension.prettyview.view;

import java.util.Objects;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelDefaultViewSelector;

public final class PrettyDefaultViewSelector
implements HttpPanelDefaultViewSelector {
  /**
   * Where this selector has to sit among ZAP's own.
   *
   * <p>ZAP sorts the default view selectors by order ascending and takes the first one that matches, so a
   * lower number wins. It ships an image selector at 20 and large request and response selectors at 50, so
   * 30 puts this in front of the large-body views while still letting an image response open in the image
   * view rather than being prettified as if it were text.
   */
  public static final int IMAGE_SELECTOR_ORDER = 20;
  public static final int LARGE_SELECTOR_ORDER = 50;
  public static final int ORDER = 30;
  private final String name;
  private final String viewName;
  private final boolean request;
  private final int maxPrettifyChars;

  public PrettyDefaultViewSelector(
      String name, String viewName, boolean request, int maxPrettifyChars) {
    this.name = Objects.requireNonNull(name, "name");
    this.viewName = Objects.requireNonNull(viewName, "viewName");
    this.request = request;
    this.maxPrettifyChars = maxPrettifyChars;
  }

  /**
   * Whether a body of this length is one Pretty View should be the default view for.
   *
   * <p>Nothing to prettify means nothing to offer: past the prettify threshold the add-on hands the body
   * back verbatim, so a raw text view is the better default and ZAP's own large body view, which sits at
   * order 50 and takes anything from 100 KB, is allowed to win instead.
   *
   * @param bodyLength length of the body in characters
   * @param maxPrettifyChars the prettify threshold
   * @return true if Pretty View should be the default view
   */
  public static boolean claimsDefaultView(int bodyLength, int maxPrettifyChars) {
    return bodyLength > 0 && bodyLength <= maxPrettifyChars;
  }

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public boolean matchToDefaultView(Message message) {
    if (!(message instanceof HttpMessage)) {
      return false;
    }
    HttpMessage httpMessage = (HttpMessage)message;
    try {
      int length = this.request ? httpMessage.getRequestBody().length() : httpMessage.getResponseBody().length();
      return PrettyDefaultViewSelector.claimsDefaultView(length, this.maxPrettifyChars);
    }
    catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public String getViewName() {
    return this.viewName;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }
}
package org.zaproxy.zap.extension.prettyview.ui;

import org.parosproxy.paros.network.HttpHeader;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.extension.httppanel.view.AbstractStringHttpPanelViewModel;

public class PrettyViewModel
extends AbstractStringHttpPanelViewModel {
  private static final String CONTENT_TYPE_HEADER = "Content-Type";
  private static final String HEADER_BODY_SEPARATOR = "";
  private final boolean request;
  private String contentType = "";

  public PrettyViewModel(boolean request) {
    this.request = request;
  }

  public boolean isRequest() {
    return this.request;
  }

  @Override
  public void setMessage(Message message) {
    this.contentType = this.extractContentType(message);
    super.setMessage(message);
  }

  @Override
  public void clear() {
    super.clear();
    this.contentType = HEADER_BODY_SEPARATOR;
  }

  @Override
  public String getData() {
    Message message = this.getMessage();
    if (!(message instanceof HttpMessage)) {
      return HEADER_BODY_SEPARATOR;
    }
    HttpMessage httpMessage = (HttpMessage)message;
    try {
      HttpHeader header = this.headerOf(httpMessage);
      if (header == null || header.isEmpty() && !this.request) {
        return HEADER_BODY_SEPARATOR;
      }
      String headerText = header.toString().replaceAll("\r\n", "\n");
      return headerText + this.bodyOf(httpMessage);
    }
    catch (RuntimeException e) {
      return HEADER_BODY_SEPARATOR;
    }
  }

  @Override
  public void setData(String data) {
  }

  public String getContentType() {
    return this.contentType;
  }

  private HttpHeader headerOf(HttpMessage httpMessage) {
    return this.request ? httpMessage.getRequestHeader() : httpMessage.getResponseHeader();
  }

  private String bodyOf(HttpMessage httpMessage) {
    return this.request ? String.valueOf(httpMessage.getRequestBody()) : String.valueOf(httpMessage.getResponseBody());
  }

  private String extractContentType(Message message) {
    if (!(message instanceof HttpMessage)) {
      return HEADER_BODY_SEPARATOR;
    }
    HttpMessage httpMessage = (HttpMessage)message;
    HttpHeader header = this.headerOf(httpMessage);
    if (header == null) {
      return HEADER_BODY_SEPARATOR;
    }
    try {
      String value = header.getHeader(CONTENT_TYPE_HEADER);
      return value == null ? HEADER_BODY_SEPARATOR : value;
    }
    catch (RuntimeException e) {
      return HEADER_BODY_SEPARATOR;
    }
  }
}


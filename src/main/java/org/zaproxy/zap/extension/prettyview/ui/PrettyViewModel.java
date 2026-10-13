package org.zaproxy.zap.extension.prettyview.ui;

import org.parosproxy.paros.network.HttpHeader;
import org.parosproxy.paros.network.HttpMalformedHeaderException;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.extension.httppanel.view.AbstractStringHttpPanelViewModel;

public class PrettyViewModel
extends AbstractStringHttpPanelViewModel {
  private static final String CONTENT_TYPE_HEADER = "Content-Type";
  private static final String HEADER_BODY_SEPARATOR = "";
  private final boolean request;
  private final boolean bodyOnly;
  private final boolean headerOnly;
  private String contentType = "";

  public PrettyViewModel(boolean request) {
    this(request, false, false);
  }

  public PrettyViewModel(boolean request, boolean bodyOnly) {
    this(request, bodyOnly, false);
  }

  public PrettyViewModel(boolean request, boolean bodyOnly, boolean headerOnly) {
    this.request = request;
    this.bodyOnly = bodyOnly;
    this.headerOnly = headerOnly;
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
      if (this.bodyOnly) {
        return this.bodyOf(httpMessage);
      }
      if (this.headerOnly) {
        return this.headerTextOf(httpMessage);
      }
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
    // Core parity: the view hands display text to the model and the model persists it, exactly
    // like RequestBodyStringHttpPanelViewModel.setData. A body-only view (split Header/Body
    // component) replaces just the body; a combined view splits the header back off first.
    Message message = this.getMessage();
    if (!(message instanceof HttpMessage)) {
      return;
    }
    HttpMessage httpMessage = (HttpMessage)message;
    try {
      if (this.bodyOnly) {
        if (this.request) {
          httpMessage.setRequestBody(data == null ? "" : data);
        } else {
          httpMessage.setResponseBody(data == null ? "" : data);
        }
        return;
      }
      if (this.headerOnly) {
        // Core parity with RequestHeaderStringHttpPanelViewModel.setData: verbatim text through
        // the strict header parse; a bad edit leaves the message untouched.
        String wire = data == null ? "" : data.replaceAll("(?<!\r)\n", "\r\n");
        if (this.request) {
          httpMessage.setRequestHeader(wire);
        } else {
          httpMessage.setResponseHeader(wire);
        }
        return;
      }
      String[] parts = PrettyViewModel.split(data);
      PrettyViewModel.writeCombined(httpMessage, this.request, parts[0], parts[1]);
    }
    catch (RuntimeException | HttpMalformedHeaderException e) {
      // Leave the message untouched when the header no longer parses.
    }
  }

  private static void writeCombined(HttpMessage httpMessage, boolean request, String header, String body)
      throws HttpMalformedHeaderException {
    if (request) {
      httpMessage.setRequestHeader(PrettyViewModel.toWireForm(header));
      httpMessage.setRequestBody(body);
    } else {
      httpMessage.setResponseHeader(PrettyViewModel.toWireForm(header));
      httpMessage.setResponseBody(body);
    }
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

  private String headerTextOf(HttpMessage httpMessage) {
    HttpHeader header = this.headerOf(httpMessage);
    if (header == null) {
      return "";
    }
    return header.toString().replaceAll("\r\n", "\n");
  }

  static String[] split(String document) {
    // Normalise first: a document holding "\r\n\r\n" (pasted or typed Windows endings) contains
    // "\n\r", never "\n\n", so searching the raw text would miss the header/body boundary and the
    // whole document would be saved as the header with an empty body.
    String text = document == null ? "" : document.replace("\r\n", "\n").replace('\r', '\n');
    int blank = text.indexOf("\n\n");
    if (blank < 0) {
      return new String[]{text, ""};
    }
    return new String[]{text.substring(0, blank), text.substring(blank + 2)};
  }

  static String toWireForm(String header) {
    return header.replaceAll("(?<!\r)\n", "\r\n");
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


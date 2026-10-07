package org.zaproxy.zap.extension.prettyview.ui;

import org.parosproxy.paros.network.HttpMalformedHeaderException;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.zap.extension.httppanel.Message;

final class PrettyWriteBack {
  private PrettyWriteBack() {
  }

  static boolean apply(Message message, boolean request, String document) {
    if (!(message instanceof HttpMessage)) {
      return false;
    }
    HttpMessage httpMessage = (HttpMessage)message;
    String[] parts = PrettyWriteBack.split(document);
    try {
      if (request) {
        httpMessage.setRequestHeader(PrettyWriteBack.toWireForm(parts[0]));
        httpMessage.setRequestBody(parts[1]);
      } else {
        httpMessage.setResponseHeader(PrettyWriteBack.toWireForm(parts[0]));
        httpMessage.setResponseBody(parts[1]);
      }
      return true;
    }
    catch (RuntimeException | HttpMalformedHeaderException e) {
      return false;
    }
  }

  static String[] split(String document) {
    String text = document == null ? "" : document;
    int blank = text.indexOf("\n\n");
    if (blank < 0) {
      return new String[]{text, ""};
    }
    return new String[]{text.substring(0, blank), text.substring(blank + 2)};
  }

  static String toWireForm(String header) {
    return header.replaceAll("(?<!\r)\n", "\r\n");
  }
}


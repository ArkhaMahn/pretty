package org.zaproxy.zap.extension.prettyview.detect;

public final class MessageSplitter {
  private MessageSplitter() {
  }

  public static Parts split(String message) {
    if (message == null || message.isEmpty()) {
      return new Parts("", "");
    }
    String normalised = MessageSplitter.normalise(message);
    int idx = normalised.indexOf("\n\n");
    if (idx < 0) {
      return new Parts(MessageSplitter.trimTrailingNewlines(normalised), "");
    }
    return new Parts(normalised.substring(0, idx), MessageSplitter.trimLeadingNewlines(normalised.substring(idx + 2)));
  }

  public static String normalise(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    return text.replace("\r\n", "\n").replace('\r', '\n');
  }

  private static String trimTrailingNewlines(String value) {
    int end;
    for (end = value.length(); end > 0 && value.charAt(end - 1) == '\n'; --end) {
    }
    return value.substring(0, end);
  }

  private static String trimLeadingNewlines(String value) {
    int start;
    for (start = 0; start < value.length() && value.charAt(start) == '\n'; ++start) {
    }
    return value.substring(start);
  }

  public static final class Parts {
    private final String headers;
    private final String body;

    Parts(String headers, String body) {
      this.headers = headers;
      this.body = body;
    }

    public String getHeaders() {
      return this.headers;
    }

    public String getBody() {
      return this.body;
    }

    public boolean hasBody() {
      return !this.body.isEmpty();
    }

    public String join() {
      if (!this.hasBody()) {
        return this.headers;
      }
      return this.headers + "\n\n" + this.body;
    }
  }
}


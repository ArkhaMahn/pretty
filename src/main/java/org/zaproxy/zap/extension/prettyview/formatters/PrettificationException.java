package org.zaproxy.zap.extension.prettyview.formatters;

public class PrettificationException
extends Exception {
  private static final long serialVersionUID = 1L;

  public PrettificationException(String message, Throwable cause) {
    super(message, cause);
  }

  public PrettificationException(String message) {
    super(message);
  }
}


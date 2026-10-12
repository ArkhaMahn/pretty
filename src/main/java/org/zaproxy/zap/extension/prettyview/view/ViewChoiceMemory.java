package org.zaproxy.zap.extension.prettyview.view;

/**
 * Remembers which side of the main window the user took away from Pretty View.
 *
 * <p>ZAP re-selects the default view on every message, so without this Pretty would grab the tab
 * back on every click after the user picked another view. While a side is suppressed, our default
 * selector declines and the user's tab stands. Picking Pretty re-arms the default. One instance is
 * shared by the extension, the view tracker and every selector the factories mint.
 */
public final class ViewChoiceMemory {
  private volatile boolean requestSuppressed = true;
  private volatile boolean responseSuppressed = true;

  /** Whether our default claim is currently stood down for request or response panels. */
  public boolean isSuppressed(boolean request) {
    return request ? this.requestSuppressed : this.responseSuppressed;
  }

  /** Stands our default claim down: the user's tab stays put on the next messages. */
  public void suppress(boolean request) {
    if (request) {
      this.requestSuppressed = true;
    } else {
      this.responseSuppressed = true;
    }
  }

  /** Re-arms our default claim after the user picked Pretty again. */
  public void clear(boolean request) {
    if (request) {
      this.requestSuppressed = false;
    } else {
      this.responseSuppressed = false;
    }
  }
}

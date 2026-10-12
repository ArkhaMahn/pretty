package org.zaproxy.zap.extension.prettyview.view;

import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelView;
import org.zaproxy.zap.extension.prettyview.ui.CustomPrettyView;

/**
 * Watches tab switches on the main request and response panels and tells {@link ViewChoiceMemory}
 * when the user took a tab away from Pretty View.
 *
 * <p>Only genuine choices suppress the default: a switch counts when a message is showing and our
 * own selector would have claimed it - over-threshold bodies and foreign message types never
 * suppress, because Pretty was never in the running for those. Picking a Pretty tab re-arms the
 * default. Switches on any other panel (dialogs we never see) are ignored by identity.
 */
public final class ViewChoiceTracker {
  private final Object requestPanel;
  private final Object responsePanel;
  private final ViewChoiceMemory memory;
  private final int maxPrettifyChars;

  public ViewChoiceTracker(
      Object requestPanel, Object responsePanel, ViewChoiceMemory memory, int maxPrettifyChars) {
    this.requestPanel = requestPanel;
    this.responsePanel = responsePanel;
    this.memory = memory;
    this.maxPrettifyChars = maxPrettifyChars;
  }

  /**
   * Records a tab switch. Call with the event source, the views on either side of it, and the
   * message showing at the time (possibly {@code null}).
   */
  public void viewSelected(
      Object source, HttpPanelView previous, HttpPanelView current, Message message) {
    boolean requestSide;
    if (source == this.requestPanel) {
      requestSide = true;
    } else if (source == this.responsePanel) {
      requestSide = false;
    } else {
      return;
    }
    if (current instanceof CustomPrettyView) {
      this.memory.clear(requestSide);
      return;
    }
    if (message == null) {
      return;
    }
    if (PrettyDefaultViewSelector.matchesMessage(message, requestSide, this.maxPrettifyChars)) {
      this.memory.suppress(requestSide);
    }
  }
}

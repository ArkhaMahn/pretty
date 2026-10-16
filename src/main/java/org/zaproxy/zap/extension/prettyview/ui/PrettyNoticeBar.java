package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

public final class PrettyNoticeBar
extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final int BAR_HEIGHT = 22;
  private final JLabel label;

  public PrettyNoticeBar() {
    super(new BorderLayout());
    this.setOpaque(true);
    this.label = new JLabel(" ", 10);
    this.label.setFont(this.label.getFont().deriveFont(0, 11.0f));
    this.add((Component)this.label, "Center");
    this.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
    this.setPreferredSize(new Dimension(100, BAR_HEIGHT));
    this.setMinimumSize(new Dimension(100, BAR_HEIGHT));
    this.setMaximumSize(new Dimension(Integer.MAX_VALUE, BAR_HEIGHT));
  }

  public void showLoading(String detail) {
    this.setMessage("Formatting" + (String)(detail == null || detail.isEmpty() ? "" : " " + detail) + "...");
    this.label.setForeground(new Color(136, 136, 136));
  }

  public void showThresholdNotice(String detail) {
    this.setMessage(detail);
    this.label.setForeground(new Color(178, 122, 0));
  }

  public void showFallbackNotice(String detail) {
    this.setMessage(detail);
    this.label.setForeground(new Color(178, 122, 0));
  }

  public void clear() {
    this.setMessage("");
  }

  private void setMessage(String message) {
    this.label.setText(message.isEmpty() ? " " : message);
    this.getAccessibleContext().setAccessibleName(message.isEmpty() ? "No message" : message);
  }
}


package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

public final class PrettyNoticeBar
extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final int BAR_HEIGHT = 22;
  private static final int SPINNER_WIDTH = 14;
  private static final String[] SPINNER_FRAMES = new String[]{"-", "\\", "|", "/"};
  private final JLabel label;
  private final JPanel spinnerSlot;
  private final JLabel spinnerLabel;
  private int spinnerIndex;

  public PrettyNoticeBar() {
    super(new BorderLayout());
    this.setOpaque(true);
    this.label = new JLabel(" ", 10);
    this.label.setFont(this.label.getFont().deriveFont(0, 11.0f));
    this.add((Component)this.label, "Center");
    this.spinnerLabel = new JLabel(" ", 0);
    this.spinnerLabel.setFont(new Font("Monospaced", 0, 11));
    this.spinnerLabel.setVisible(false);
    this.spinnerLabel.setPreferredSize(new Dimension(14, 22));
    this.spinnerLabel.setMinimumSize(new Dimension(14, 22));
    this.spinnerLabel.setMaximumSize(new Dimension(14, 22));
    this.spinnerSlot = new JPanel(new BorderLayout());
    this.spinnerSlot.setOpaque(false);
    this.spinnerSlot.setPreferredSize(new Dimension(14, 22));
    this.spinnerSlot.add((Component)this.spinnerLabel, "Center");
    this.add((Component)this.spinnerSlot, "Before");
    this.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
    this.setPreferredSize(new Dimension(100, 22));
    this.setMinimumSize(new Dimension(100, 22));
    this.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
  }

  public void showLoading(String detail) {
    this.setMessage("Formatting" + (String)(detail == null || detail.isEmpty() ? "" : " " + detail) + "...");
    this.label.setForeground(new Color(136, 136, 136));
    this.spinnerLabel.setVisible(true);
    this.setSpinnerFrame(0);
  }

  public void advanceSpinner() {
    if (!this.spinnerLabel.isVisible()) {
      return;
    }
    int next = (this.spinnerIndex + 1) % SPINNER_FRAMES.length;
    this.setSpinnerFrame(next);
  }

  private void setSpinnerFrame(int index) {
    this.spinnerIndex = index;
    this.spinnerLabel.setText(SPINNER_FRAMES[index]);
  }

  public void showThresholdNotice(String detail) {
    this.stopSpinner();
    this.setMessage(detail);
    this.label.setForeground(new Color(178, 122, 0));
  }

  public void showFallbackNotice(String detail) {
    this.stopSpinner();
    this.setMessage(detail);
    this.label.setForeground(new Color(178, 122, 0));
  }

  public void clear() {
    this.stopSpinner();
    this.setMessage("");
  }

  private void stopSpinner() {
    this.spinnerLabel.setVisible(false);
    this.spinnerLabel.setText(" ");
  }

  private void setMessage(String message) {
    this.label.setText(message.isEmpty() ? " " : message);
    this.getAccessibleContext().setAccessibleName(message.isEmpty() ? "No message" : message);
  }

  public String getMessage() {
    String text = this.label.getText();
    return " ".equals(text) ? "" : text;
  }
}


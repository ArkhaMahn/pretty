package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

/**
 * The find bar that sits above the editor, and the only piece of the panel that searches.
 *
 * <p>ZAP's own request and response views put their search where the message is, so this is the same idea
 * brought into the Pretty view: the bar is opened from the right-click menu or with Ctrl+F, it can be left
 * open while editing, and Enter / Shift+Enter / F3 / Shift+F3 walk the matches. Matching is done by RSTA's
 * own {@link SearchEngine}, so plain text, case sensitivity, whole words and regular expressions all
 * behave exactly as they do in the editor ZAP already ships rather than through a second implementation.
 */
final class PrettySearchBar extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final Color NO_MATCH = new Color(178, 122, 0);

  private final RSyntaxTextArea textArea;
  private final JTextField field = new JTextField(18);
  private final JCheckBox matchCase = new JCheckBox("Aa");
  private final JCheckBox wholeWord = new JCheckBox("ab");
  private final JCheckBox regex = new JCheckBox(".*");
  private final JLabel status = new JLabel(" ");
  private final JButton previous = new JButton("\u25b2");
  private final JButton next = new JButton("\u25bc");
  private final JButton close = new JButton("\u2715");

  PrettySearchBar(RSyntaxTextArea textArea) {
    super(new BorderLayout(6, 0));
    this.textArea = textArea;
    this.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
    this.setVisible(false);

    JLabel caption = new JLabel("Find:");
    this.add((Component)caption, "West");

    this.add((Component)this.field, "Center");
    this.decorateToggle(this.matchCase, "Match case");
    this.decorateToggle(this.wholeWord, "Whole words");
    this.decorateToggle(this.regex, "Regular expression");

    JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    controls.setOpaque(false);
    controls.add(this.matchCase);
    controls.add(this.wholeWord);
    controls.add(this.regex);
    controls.add(this.previous);
    controls.add(this.next);
    controls.add(this.status);
    controls.add(this.close);
    this.add((Component)controls, "East");

    this.previous.setMargin(new java.awt.Insets(0, 6, 0, 6));
    this.next.setMargin(new java.awt.Insets(0, 6, 0, 6));
    this.close.setMargin(new java.awt.Insets(0, 6, 0, 6));
    this.previous.setToolTipText("Previous match (Shift+F3)");
    this.next.setToolTipText("Next match (F3)");
    this.close.setToolTipText("Close the find bar (Esc)");
    this.status.setPreferredSize(new Dimension(80, this.status.getPreferredSize().height));

    this.field.addActionListener(event -> this.find(true));
    this.field.addKeyListener(new java.awt.event.KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
          PrettySearchBar.this.close();
        }
      }
    });
    this.matchCase.addItemListener(event -> this.refresh());
    this.wholeWord.addItemListener(event -> this.refresh());
    this.regex.addItemListener(event -> this.refresh());
    this.previous.addActionListener(event -> this.find(false));
    this.next.addActionListener(event -> this.find(true));
    this.close.addActionListener(event -> this.close());

    // The bar closes on Escape from any of its controls, not just the text field.
    this.bindEscape(this);
    this.bindEscape(this.field);
  }

  private void decorateToggle(JCheckBox toggle, String tip) {
    toggle.setOpaque(false);
    toggle.setToolTipText(tip);
    toggle.setFocusable(false);
  }

  private void bindEscape(JComponent component) {
    component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "pretty-find-close");
    component.getActionMap().put("pretty-find-close", new AbstractAction() {
      private static final long serialVersionUID = 1L;

      @Override
      public void actionPerformed(ActionEvent event) {
        PrettySearchBar.this.close();
      }
    });
  }

  boolean isOpen() {
    return this.isVisible();
  }

  /** Opens the bar, pre-filling it from a selection so Ctrl+F on a word searches for that word. */
  void open(String selection) {
    this.setVisible(true);
    if (selection != null && !selection.isEmpty() && selection.indexOf('\n') < 0) {
      this.field.setText(selection);
    }
    SwingUtilities.invokeLater(() -> {
      this.field.selectAll();
      this.field.requestFocusInWindow();
    });
    this.refresh();
    this.revalidate();
    this.repaint();
  }

  /** Closes the bar and drops the match highlights it left behind. */
  void close() {
    this.setVisible(false);
    SearchEngine.markAll(this.textArea, new SearchContext(""));
    this.textArea.requestFocusInWindow();
    this.revalidate();
    this.repaint();
  }

  boolean findNext() {
    return this.find(true);
  }

  boolean findPrevious() {
    return this.find(false);
  }

  private boolean find(boolean forward) {
    if (this.field.getText().isEmpty()) {
      this.refresh();
      return false;
    }
    SearchResult result = SearchEngine.find(this.textArea, this.context(forward));
    this.updateStatus(result.getMarkedCount());
    return result.getMarkedCount() > 0;
  }

  private void refresh() {
    if (this.field.getText().isEmpty()) {
      SearchEngine.markAll(this.textArea, new SearchContext(""));
      this.status.setText(" ");
      this.status.setForeground(this.field.getForeground());
      return;
    }
    SearchResult result = SearchEngine.markAll(this.textArea, this.context(true));
    this.updateStatus(result.getMarkedCount());
  }

  private void updateStatus(int count) {
    if (count > 0) {
      this.status.setText(count + (count == 1 ? " match" : " matches"));
      this.status.setForeground(this.field.getForeground());
    } else {
      this.status.setText("No matches");
      this.status.setForeground(NO_MATCH);
    }
  }

  private SearchContext context(boolean forward) {
    SearchContext context = new SearchContext(this.field.getText(), forward);
    context.setMatchCase(this.matchCase.isSelected());
    context.setWholeWord(this.wholeWord.isSelected());
    context.setRegularExpression(this.regex.isSelected());
    context.setSearchWrap(true);
    context.setMarkAll(true);
    return context;
  }
}
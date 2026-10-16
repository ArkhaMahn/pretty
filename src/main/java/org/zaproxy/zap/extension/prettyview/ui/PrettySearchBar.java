package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;

/**
 * The find bar that sits above the editor, and the only piece of the panel that searches.
 *
 * <p>ZAP's own request and response views put their search where the message is, so this is the same idea
 * brought into the Pretty view: the bar is opened from the right-click menu or with Ctrl+F, it can be left
 * open while editing, and Enter / Shift+Enter / F3 / Shift+F3 walk the matches. Matching runs over the
 * document text with plain text, case sensitivity, whole words and regular expressions, and every
 * match is marked through the standard highlighter layer.
 */
final class PrettySearchBar extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final Color NO_MATCH = new Color(178, 122, 0);
  /**
   * Longest pattern evaluated against the document. A few dozen characters are plenty for a find
   * bar, and an attacker-sized selection must never become a megabyte regex over a megabyte body.
   */
  private static final int MAX_PATTERN_CHARS = 512;
  /** Most matches ever marked at once; the count still reports the true total. */
  private static final int MAX_MARKS = 2000;

  private final JTextPane textPane;
  private final JTextField field = new JTextField(18);
  private final JCheckBox matchCase = new JCheckBox("Aa");
  private final JCheckBox wholeWord = new JCheckBox("ab");
  private final JCheckBox regex = new JCheckBox(".*");
  private final JLabel status = new JLabel(" ");
  private final JButton previous = new JButton("\u25b2");
  private final JButton next = new JButton("\u25bc");
  private final JButton close = new JButton("\u2715");
  /** Match starts in document order; the caret walks this list. */
  private final List<int[]> matches = new ArrayList<>();
  /** Index into {@link #matches} of the current match, or -1. */
  private int current = -1;
  private final Highlighter.HighlightPainter markPainter =
      new DefaultHighlighter.DefaultHighlightPainter(new Color(0, 122, 204, 90));

  PrettySearchBar(JTextPane textPane) {
    super(new BorderLayout(6, 0));
    this.textPane = textPane;
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
    this.clearMarks();
    this.matches.clear();
    this.current = -1;
    this.textPane.requestFocusInWindow();
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
    if (this.matches.isEmpty()) {
      this.refresh();
    }
    if (this.matches.isEmpty()) {
      return false;
    }
    if (forward) {
      this.current = (this.current + 1) % this.matches.size();
    } else {
      this.current = (this.current + this.matches.size() - 1) % this.matches.size();
    }
    this.showCurrent();
    return true;
  }

  /** Re-marks every match and parks the caret on the first one. */
  private void refresh() {
    this.clearMarks();
    this.matches.clear();
    this.current = -1;
    if (this.field.getText().isEmpty()) {
      this.status.setText(" ");
      this.status.setForeground(this.field.getForeground());
      return;
    }
    try {
      Pattern pattern = this.pattern();
      Matcher matcher = pattern.matcher(this.textPane.getDocument()
          .getText(0, this.textPane.getDocument().getLength()));
      Highlighter highlighter = this.textPane.getHighlighter();
      int marks = 0;
      while (matcher.find()) {
        if (matcher.end() == matcher.start()) {
          // An empty match would park the walk; step past it.
          if (matcher.end() >= this.textPane.getDocument().getLength()) {
            break;
          }
          matcher.region(matcher.end() + 1, this.textPane.getDocument().getLength());
          continue;
        }
        this.matches.add(new int[] {matcher.start(), matcher.end()});
        if (marks < MAX_MARKS) {
          highlighter.addHighlight(matcher.start(), matcher.end(), this.markPainter);
          ++marks;
        }
      }
      if (!this.matches.isEmpty()) {
        this.current = 0;
        this.showCurrent();
      }
      this.updateStatus(this.matches.size());
    } catch (PatternSyntaxException e) {
      this.status.setText("Bad regex");
      this.status.setForeground(NO_MATCH);
    } catch (RuntimeException | BadLocationException e) {
      this.status.setText("Search failed");
      this.status.setForeground(NO_MATCH);
    }
  }

  private void showCurrent() {
    if (this.current < 0 || this.current >= this.matches.size()) {
      return;
    }
    int[] match = this.matches.get(this.current);
    this.textPane.setCaretPosition(match[0]);
    this.textPane.moveCaretPosition(match[1]);
    try {
      Rectangle at = this.textPane.modelToView(match[1]);
      if (at != null) {
        this.textPane.scrollRectToVisible(at);
      }
    } catch (BadLocationException e) {
      // The document changed under the highlight; the selection still stands.
    }
  }

  private void clearMarks() {
    this.textPane.getHighlighter().removeAllHighlights();
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

  /** Compiles the field into a pattern honouring the toggles. */
  private Pattern pattern() {
    // Never evaluate an attacker-sized pattern: open() pre-fills the field from the selection,
    // which is body text when Ctrl+F follows a select-all. Longer input is still searchable by
    // its head rather than crashing or backtracking over the whole document.
    String text = this.field.getText();
    if (text.length() > MAX_PATTERN_CHARS) {
      text = text.substring(0, MAX_PATTERN_CHARS);
    }
    int flags = this.matchCase.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    if (this.regex.isSelected()) {
      String body = this.wholeWord.isSelected() ? "\\b(?:" + text + ")\\b" : text;
      return Pattern.compile(body, flags);
    }
    String body = this.wholeWord.isSelected() ? "\\b\\Q" + text + "\\E\\b" : "\\Q" + text + "\\E";
    return Pattern.compile(body, flags);
  }
}

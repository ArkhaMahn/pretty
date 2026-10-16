package org.zaproxy.zap.extension.prettyview.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * The editor the Pretty view renders into, with more air between its lines than RSTA gives by default.
 *
 * <p>RSTA fixes row spacing at the font's own line height, which packs the lines tightly enough that a
 * wall of JSON or HTML reads as a solid block. The syntax view, the token painter and every gutter
 * component ask the text area for {@link #getLineHeight()}, so raising it here spaces the body, the line
 * numbers and the fold/overview markers together; writing the private field RSTA computes instead would
 * leave the gutter misaligned with the text. The extra is a fraction of the font's line height rather
 * than a fixed number of pixels, so it follows a font change made under ZAP's Configure Fonts.
 */
final class PrettyTextArea extends RSyntaxTextArea {
  private static final long serialVersionUID = 1L;

  /** How much of the font's own line height is added on top, as a proportion of it. */
  private static final float EXTRA_RATIO = 0.35f;

  /** The smallest increase in pixels, so a small font still gets a visible gap. */
  private static final int MIN_EXTRA = 2;

  /**
   * Whether construction finished. RSTA's own initialisation calls back into {@link
   * #setLineWrap} (via {@code setTabSize}) while the fold manager is still null, so the
   * folding half below stays out until the area exists.
   */
  private boolean ready;

  PrettyTextArea() {
    super();
    this.ready = true;
  }

  @Override
  public int getLineHeight() {
    int base = super.getLineHeight();
    return base + Math.max(MIN_EXTRA, Math.round(base * EXTRA_RATIO));
  }

  /**
   * No hover tooltips, ever: no fold previews, no link or occurrence tips, nothing following
   * the mouse over the message.
   */
  @Override
  public String getToolTipText(java.awt.event.MouseEvent event) {
    return null;
  }

  /**
   * Burp's editor shapes its own {@code setLineWrap} the same way: the stock flag plus the
   * housekeeping that belongs with it. A fold gutter cannot share its lines with wrapped rows,
   * so folding follows the wrap flag here instead of at every call site - wrapping drops it,
   * unwrapping restores it - and callers only ever set the wrap flag itself.
   */
  @Override
  public void setLineWrap(boolean wrap) {
    super.setLineWrap(wrap);
    if (this.ready) {
      this.setCodeFoldingEnabled(!wrap);
    }
  }
}

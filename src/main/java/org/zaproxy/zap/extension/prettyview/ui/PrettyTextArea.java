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

  @Override
  public int getLineHeight() {
    int base = super.getLineHeight();
    return base + Math.max(MIN_EXTRA, Math.round(base * EXTRA_RATIO));
  }
}

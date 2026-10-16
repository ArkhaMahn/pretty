package org.zaproxy.zap.extension.prettyview.ui;

import java.util.HashMap;
import java.util.Map;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenImpl;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

/**
 * A syntax document that remembers the tokens of every line it lexes.
 *
 * <p>Stock documents lex a line anew on every call, and the wrapped view asks for every line on
 * every paint: scrolling a page with over-long lines re-lexes hundreds of kilobytes per scroll
 * step, seconds against a 16 ms frame. Remembering a line's tokens turns every paint after the
 * first into pointer walks.
 *
 * <p>The remembered chains are owned copies, never the maker's own lists: makers mint their
 * tokens from a pooled handful of objects that the next call reuses, so caching a delegate list
 * would corrupt it - values overwritten, chains cycled, rows measured into the millions of
 * pixels. Any edit, style swap or maker change drops the whole cache; edits are rare next to
 * paints, so clearing everything stays cheaper than tracking lines.
 */
final class CachingRSyntaxDocument extends RSyntaxDocument {
  private static final long serialVersionUID = 1L;

  /** Owned token chains by line index, filled as lines are lexed. */
  private final Map<Integer, Token> tokensByLine = new HashMap<>();

  CachingRSyntaxDocument(TokenMakerFactory factory, String style) {
    super(factory, style);
  }

  @Override
  public Token getTokenListForLine(int line) {
    // Null until construction finishes: the superclass constructor sets the syntax style,
    // which routes through the clearing overrides below.
    if (this.tokensByLine == null) {
      return super.getTokenListForLine(line);
    }
    Token remembered = this.tokensByLine.get(line);
    if (remembered != null) {
      return remembered;
    }
    Token lexed = super.getTokenListForLine(line);
    if (lexed == null) {
      return null;
    }
    try {
      Token owned = CachingRSyntaxDocument.ownChain(lexed);
      this.tokensByLine.put(line, owned);
      return owned;
    } catch (RuntimeException e) {
      // A line that cannot be copied is lexed stock instead of cached; never worse than before.
      return lexed;
    }
  }

  /** Drops the remembered chains, if construction finished. */
  private void clearTokens() {
    if (this.tokensByLine != null) {
      this.tokensByLine.clear();
    }
  }

  /**
   * Copies a lexed chain into tokens this cache owns. The pooled originals are left linked as
   * found; only the copies are chained together.
   */
  private static Token ownChain(Token lexed) {
    TokenImpl head = null;
    TokenImpl tail = null;
    for (Token token = lexed; token != null; token = token.getNextToken()) {
      TokenImpl owned = new TokenImpl(token);
      owned.setNextToken(null);
      if (head == null) {
        head = owned;
      } else {
        tail.setNextToken(owned);
      }
      tail = owned;
    }
    return head;
  }

  @Override
  protected void insertUpdate(AbstractDocument.DefaultDocumentEvent event, AttributeSet attributes) {
    this.clearTokens();
    super.insertUpdate(event, attributes);
  }

  @Override
  protected void removeUpdate(AbstractDocument.DefaultDocumentEvent event) {
    this.clearTokens();
    super.removeUpdate(event);
  }

  @Override
  protected void fireChangedUpdate(javax.swing.event.DocumentEvent event) {
    this.clearTokens();
    super.fireChangedUpdate(event);
  }

  @Override
  public void setSyntaxStyle(TokenMaker maker) {
    this.clearTokens();
    super.setSyntaxStyle(maker);
  }

  @Override
  public void setSyntaxStyle(String style) {
    this.clearTokens();
    super.setSyntaxStyle(style);
  }
}

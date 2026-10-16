package org.zaproxy.zap.extension.prettyview.ui;

import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenImpl;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Reads JavaScript template-literal text as plain text.
 *
 * <p>Burp paints the backquote chunks of a template literal the same grey as its quotes, while
 * the stock tokenizer emits them as string tokens. Copying the list and retyping those tokens
 * keeps the lexing identical while the literal reads quote-grey; only ever stacked over the
 * JavaScript maker, where backquote tokens are template text.
 */
final class JsBackquoteTokenMaker extends TokenMakerBase {

  private final TokenMaker delegate;

  JsBackquoteTokenMaker(TokenMaker delegate) {
    this.delegate = delegate;
  }

  @Override
  public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
    Token head = this.delegate.getTokenList(text, initialTokenType, startOffset);
    TokenImpl first = null;
    TokenImpl current = null;
    for (Token token = head; token != null; token = token.getNextToken()) {
      TokenImpl copy = new TokenImpl(token);
      if (copy.getType() == TokenTypes.LITERAL_BACKQUOTE) {
        copy.setType(TokenTypes.IDENTIFIER);
      }
      if (first == null) {
        first = copy;
      } else {
        current.setNextToken(copy);
      }
      current = copy;
    }
    return first;
  }

  @Override
  public int getLastTokenTypeOnLine(Segment text, int initialTokenType) {
    return this.delegate.getLastTokenTypeOnLine(text, initialTokenType);
  }
}

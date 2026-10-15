package org.zaproxy.zap.extension.prettyview.ui;

import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenImpl;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Reads JSON property names as plain text.
 *
 * <p>RSTA's JSON tokenizer emits property names as variables, which Burp paints gold - but Burp
 * paints object keys the same grey as its quotes and body text, reserving gold for real variables.
 * Copying the list and retyping those tokens keeps the lexing identical while the keys read as
 * text. Only ever stacked over the JSON maker, where every variable token is a property name.
 */
final class JsonKeyTokenMaker extends TokenMakerBase {

  private final TokenMaker delegate;

  JsonKeyTokenMaker(TokenMaker delegate) {
    this.delegate = delegate;
  }

  @Override
  public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
    Token head = this.delegate.getTokenList(text, initialTokenType, startOffset);
    TokenImpl first = null;
    TokenImpl current = null;
    for (Token token = head; token != null; token = token.getNextToken()) {
      TokenImpl copy = new TokenImpl(token);
      if (copy.getType() == TokenTypes.VARIABLE) {
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

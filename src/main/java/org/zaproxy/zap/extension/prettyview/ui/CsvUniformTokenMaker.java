package org.zaproxy.zap.extension.prettyview.ui;

import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenImpl;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Reads every CSV cell as plain text.
 *
 * <p>The stock tokenizer bands columns by parity, painting every odd cell as a keyword, so data
 * reads zebra-striped and numbers never read as numbers. Copying the list and retyping those
 * cells keeps the lexing - commas, quoting and cell boundaries - identical while the content
 * reads uniformly. Only ever stacked over the CSV maker.
 */
final class CsvUniformTokenMaker extends TokenMakerBase {

  private final TokenMaker delegate;

  CsvUniformTokenMaker(TokenMaker delegate) {
    this.delegate = delegate;
  }

  @Override
  public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
    Token head = this.delegate.getTokenList(text, initialTokenType, startOffset);
    TokenImpl first = null;
    TokenImpl current = null;
    for (Token token = head; token != null; token = token.getNextToken()) {
      TokenImpl copy = new TokenImpl(token);
      if (copy.getType() == TokenTypes.DATA_TYPE) {
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

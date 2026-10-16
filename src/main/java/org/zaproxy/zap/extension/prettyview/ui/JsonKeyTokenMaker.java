package org.zaproxy.zap.extension.prettyview.ui;

import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenImpl;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Reads JSON values in their requested hues: orange keys, green strings, blue numbers and
 * booleans.
 *
 * <p>Burp paints object keys the same grey as its quotes and body text, but the requested style
 * gives keys, string values and numeric values each their own colour. The rewrite targets token
 * types no JSON body emits for another purpose - variables are always property names here,
 * doubles are always strings, ints, floats and booleans are always numbers - and the scheme
 * paints those types in the requested hues, so every other language keeps its own colours while
 * JSON reads exactly as asked. Copying the list and retyping those tokens keeps the lexing
 * identical. Only ever stacked over the JSON maker.
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
      int type = copy.getType();
      if (type == TokenTypes.VARIABLE) {
        copy.setType(TokenTypes.PREPROCESSOR);
      } else if (type == TokenTypes.LITERAL_STRING_DOUBLE_QUOTE) {
        copy.setType(TokenTypes.LITERAL_CHAR);
      } else if (type == TokenTypes.LITERAL_BOOLEAN
          || type == TokenTypes.LITERAL_NUMBER_DECIMAL_INT
          || type == TokenTypes.LITERAL_NUMBER_FLOAT) {
        copy.setType(TokenTypes.LITERAL_NUMBER_HEXADECIMAL);
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

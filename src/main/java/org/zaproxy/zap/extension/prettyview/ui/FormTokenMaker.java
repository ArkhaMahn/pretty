package org.zaproxy.zap.extension.prettyview.ui;

import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;

/**
 * Colours form bodies pair by pair: names read as keywords, values as text, with the {@code =}
 * and {@code &} separators between them.
 *
 * <p>The stock properties tokenizer this replaces stops at the first pair: it has no notion of
 * {@code &}, so in {@code a=b&b=c} everything from the first value on arrives as one string
 * token and only {@code a} ever reads as a name. Splitting pairs here keeps every name coloured
 * no matter how many follow. Values stay verbatim - percent-encoding and {@code +} are never
 * decoded - and a segment without {@code =} reads as a bare name, exactly like the properties
 * tokenizer already did. Every line stands alone, so a monster line still costs one line of
 * lexing.
 */
final class FormTokenMaker extends TokenMakerBase {

  @Override
  public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
    this.resetTokenList();
    char[] array = text.array;
    int offset = text.offset;
    int end = offset + text.count;
    int i = offset;
    while (i < end) {
      int pairEnd = i;
      while (pairEnd < end && array[pairEnd] != '&') {
        ++pairEnd;
      }
      FormTokenMaker.emitPair(this, text, array, i, pairEnd, startOffset, offset);
      if (pairEnd < end) {
        this.addToken(text, pairEnd, pairEnd, Token.SEPARATOR, startOffset + pairEnd - offset);
      }
      i = pairEnd + 1;
    }
    this.addNullToken();
    return this.firstToken;
  }

  private static void emitPair(
      FormTokenMaker maker, Segment text, char[] array, int from, int to, int startOffset,
      int offset) {
    if (from >= to) {
      return;
    }
    int equals = -1;
    for (int i = from; i < to; ++i) {
      if (array[i] == '=') {
        equals = i;
        break;
      }
    }
    if (equals < 0) {
      maker.addToken(text, from, to - 1, Token.RESERVED_WORD, startOffset + from - offset);
      return;
    }
    if (equals > from) {
      maker.addToken(text, from, equals - 1, Token.RESERVED_WORD, startOffset + from - offset);
    }
    // '=' keeps the operator token the properties tokenizer gave it, so only the previously
    // swallowed names change colour; '&' reads as the separator it is.
    maker.addToken(text, equals, equals, Token.OPERATOR, startOffset + equals - offset);
    if (equals + 1 < to) {
      maker.addToken(text, equals + 1, to - 1, Token.IDENTIFIER,
          startOffset + equals + 1 - offset);
    }
  }
}

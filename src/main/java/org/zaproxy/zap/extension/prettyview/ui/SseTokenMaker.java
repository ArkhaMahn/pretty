package org.zaproxy.zap.extension.prettyview.ui;

import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;

/**
 * Colours server-sent-events streams: field names ({@code event}, {@code data}, {@code id},
 * {@code retry}) read as keywords, values as plain text, {@code :}-led comments as comments.
 *
 * <p>Every line stands alone - the WHATWG stream format processes lines independently, so no
 * continuation state crosses a newline and a monster {@code data:} line costs exactly one line of
 * lexing.
 */
final class SseTokenMaker extends TokenMakerBase {

  @Override
  public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
    this.resetTokenList();
    char[] array = text.array;
    int offset = text.offset;
    int count = text.count;
    int end = offset + count;
    if (count == 0) {
      this.addNullToken();
      return this.firstToken;
    }
    int lineEnd = end;
    while (lineEnd > offset
        && (array[lineEnd - 1] == '\n' || array[lineEnd - 1] == '\r')) {
      --lineEnd;
    }
    if (lineEnd == offset) {
      this.addNullToken();
      return this.firstToken;
    }
    if (array[offset] == ':') {
      this.addToken(text, offset, lineEnd - 1, Token.COMMENT_EOL, startOffset);
      this.addNullToken();
      return this.firstToken;
    }
    int colon = -1;
    for (int i = offset; i < lineEnd; ++i) {
      if (array[i] == ':') {
        colon = i;
        break;
      }
    }
    if (colon < 0) {
      this.addToken(text, offset, lineEnd - 1, Token.IDENTIFIER, startOffset);
      this.addNullToken();
      return this.firstToken;
    }
    this.addToken(text, offset, colon, Token.RESERVED_WORD, startOffset);
    if (colon + 1 < lineEnd) {
      this.addToken(text, colon + 1, lineEnd - 1, Token.IDENTIFIER, startOffset + colon + 1 - offset);
    }
    this.addNullToken();
    return this.firstToken;
  }

  @Override
  public String[] getLineCommentStartAndEnd(int languageIndex) {
    return new String[] {":", null};
  }
}

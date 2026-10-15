package org.zaproxy.zap.extension.prettyview.ui;

import javax.swing.Action;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.OccurrenceMarker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Paints the HTTP header region the way Burp's Pretty does: the request/status line and every
 * header name read as one colour, header values as text.
 *
 * <p>The delegate tokenizer only ever sees the body language, so without this the headers lex as
 * whatever the body happens to be - usually flat identifiers. Offsets below {@code headerEnd}
 * (display coordinates, captured per load) are headers instead: offset zero is the request or
 * status line, and a {@code Name:} line splits into its name and its value at the colon. Anything
 * at or past the boundary goes to the delegate untouched, as does a header line with no colon.
 * Continuation state stays in delegate-land throughout: {@link #getLastTokenTypeOnLine} answers
 * from the delegate, so multi-line body constructs crossing the boundary still lex correctly.
 */
final class HeaderRegionTokenMaker extends TokenMakerBase {

  private final TokenMaker delegate;
  private final int headerEnd;

  HeaderRegionTokenMaker(TokenMaker delegate, int headerEnd) {
    this.delegate = delegate;
    this.headerEnd = headerEnd;
  }

  @Override
  public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
    if (startOffset < 0 || startOffset >= this.headerEnd) {
      return this.delegate.getTokenList(text, initialTokenType, startOffset);
    }
    this.resetTokenList();
    int end = text.offset + text.count;
    while (end > text.offset
        && (text.array[end - 1] == '\n' || text.array[end - 1] == '\r')) {
      --end;
    }
    if (end <= text.offset) {
      this.addNullToken();
      return this.firstToken;
    }
    if (startOffset == 0) {
      this.addToken(text, text.offset, end - 1, TokenTypes.ANNOTATION, startOffset);
      this.addNullToken();
      return this.firstToken;
    }
    int colon = -1;
    for (int i = text.offset; i < end; ++i) {
      char c = text.array[i];
      if (c == ':') {
        colon = i;
        break;
      }
      if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-')) {
        colon = -2;
        break;
      }
    }
    if (colon < 0) {
      this.addToken(text, text.offset, end - 1, TokenTypes.IDENTIFIER, startOffset);
    } else {
      this.addToken(text, text.offset, colon, TokenTypes.ANNOTATION, startOffset);
      if (colon + 1 < end) {
        this.addToken(text, colon + 1, end - 1, TokenTypes.IDENTIFIER,
            startOffset + colon + 1 - text.offset);
      }
    }
    this.addNullToken();
    return this.firstToken;
  }

  @Override
  public int getLastTokenTypeOnLine(Segment text, int initialTokenType) {
    return this.delegate.getLastTokenTypeOnLine(text, initialTokenType);
  }

  @Override
  public int getClosestStandardTokenTypeForInternalType(int type) {
    return this.delegate.getClosestStandardTokenTypeForInternalType(type);
  }

  @Override
  public boolean getCurlyBracesDenoteCodeBlocks(int languageIndex) {
    return this.delegate.getCurlyBracesDenoteCodeBlocks(languageIndex);
  }

  @Override
  public String[] getLineCommentStartAndEnd(int languageIndex) {
    return this.delegate.getLineCommentStartAndEnd(languageIndex);
  }

  @Override
  public Action getInsertBreakAction() {
    return this.delegate.getInsertBreakAction();
  }

  @Override
  public boolean getMarkOccurrencesOfTokenType(int type) {
    return this.delegate.getMarkOccurrencesOfTokenType(type);
  }

  @Override
  public OccurrenceMarker getOccurrenceMarker() {
    return this.delegate.getOccurrenceMarker();
  }

  @Override
  public boolean getShouldIndentNextLineAfter(Token token) {
    return this.delegate.getShouldIndentNextLineAfter(token);
  }

  @Override
  public boolean isIdentifierChar(int languageIndex, char ch) {
    return this.delegate.isIdentifierChar(languageIndex, ch);
  }

  @Override
  public boolean isMarkupLanguage() {
    return this.delegate.isMarkupLanguage();
  }
}

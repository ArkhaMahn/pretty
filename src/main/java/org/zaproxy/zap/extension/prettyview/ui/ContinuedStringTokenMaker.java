package org.zaproxy.zap.extension.prettyview.ui;

import javax.swing.Action;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.OccurrenceMarker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenImpl;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * A {@link TokenMaker} that keeps a string literal coloured after it has been cut in two.
 *
 * <p>{@link DisplayLineSplitter} hands the editor shorter lines than the formatted payload has, and a
 * break can land inside a string. The editor tokenizes one line at a time, and a token maker that is
 * told a line ends in an unterminated string still has no way to say so: the delegates the editor ships
 * with either report no continuation state at all, or one that carries the surrounding language but not
 * the open quote. So the second half of a split string is tokenized from scratch and reads as bare text,
 * which is the colouring loss this wrapper removes.
 *
 * <p>It recognises a line that ends inside a string by its last paintable token - only an unterminated
 * string is given an error token - and records the quote and the delegate's own continuation state in the
 * value it returns. The next line is then read with that value: the characters up to the closing quote
 * are emitted as one string token, and whatever follows is handed back to the delegate to tokenize as the
 * language it belongs to.
 */
final class ContinuedStringTokenMaker implements TokenMaker {

  /** Marks a token type as this wrapper's own continuation state rather than one the delegate minted. */
  private static final int MARKER = 0x40000000;
  private static final int MARKER_MASK = 0xC0000000;
  private static final int SINGLE_QUOTE = 0x00010000;
  private static final int LANGUAGE_SHIFT = 20;
  private static final int BASE_MASK = 0xFFFF;

  private final TokenMaker delegate;

  ContinuedStringTokenMaker(TokenMaker delegate) {
    this.delegate = delegate;
  }

  @Override
  public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
    if (ContinuedStringTokenMaker.isMarker(initialTokenType)) {
      return this.continueString(text, initialTokenType, startOffset);
    }
    return ContinuedStringTokenMaker.recolourOpenString(
        this.delegate.getTokenList(text, initialTokenType, startOffset));
  }

  @Override
  public int getLastTokenTypeOnLine(Segment text, int initialTokenType) {
    if (ContinuedStringTokenMaker.isMarker(initialTokenType)) {
      int close = ContinuedStringTokenMaker.indexOfQuote(text, ContinuedStringTokenMaker.markerQuote(initialTokenType));
      if (close < 0) {
        return initialTokenType;
      }
      Segment rest = new Segment(text.array, text.offset + close + 1, text.count - close - 1);
      return this.lastType(rest, ContinuedStringTokenMaker.baseOf(initialTokenType));
    }
    return this.lastType(text, initialTokenType);
  }

  /**
   * The first {@code stringLength} characters of the line are the tail of a string opened on an earlier
   * line; the rest is given back to the delegate, starting from the state the earlier line left off in.
   */
  private Token continueString(Segment text, int marker, int startOffset) {
    char quote = ContinuedStringTokenMaker.markerQuote(marker);
    int close = ContinuedStringTokenMaker.indexOfQuote(text, quote);
    int stringLength = close < 0 ? text.count : close + 1;
    int type = quote == '\''
        ? TokenTypes.LITERAL_CHAR
        : TokenTypes.LITERAL_STRING_DOUBLE_QUOTE;
    TokenImpl string = new TokenImpl(
        text.array,
        text.offset,
        text.offset + stringLength - 1,
        startOffset,
        type,
        ContinuedStringTokenMaker.languageOf(marker));
    Segment rest = new Segment(text.array, text.offset + stringLength, text.count - stringLength);
    string.setNextToken(this.delegate.getTokenList(rest, ContinuedStringTokenMaker.baseOf(marker), startOffset + stringLength));
    return string;
  }

  /** The delegate's continuation state for the line, promoted to a marker when the line ends in a string. */
  private int lastType(Segment text, int initialTokenType) {
    int last = this.delegate.getLastTokenTypeOnLine(text, initialTokenType);
    Token open = ContinuedStringTokenMaker.openString(this.delegate.getTokenList(text, initialTokenType, 0));
    if (open == null) {
      return last;
    }
    return ContinuedStringTokenMaker.marker(
        ContinuedStringTokenMaker.quoteOfType(open.getType()),
        open.getLanguageIndex(),
        last);
  }

  /** An unterminated string is the one token kind the delegate marks as an error. */
  private static Token openString(Token list) {
    Token open = null;
    for (Token token = list; token != null; token = token.getNextToken()) {
      if (!token.isPaintable()) {
        continue;
      }
      int type = token.getType();
      open = type == TokenTypes.ERROR_STRING_DOUBLE || type == TokenTypes.ERROR_CHAR ? token : null;
    }
    return open;
  }

  private static Token recolourOpenString(Token list) {
    for (Token token = list; token != null; token = token.getNextToken()) {
      int type = token.getType();
      if (type == TokenTypes.ERROR_STRING_DOUBLE) {
        token.setType(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE);
      } else if (type == TokenTypes.ERROR_CHAR) {
        token.setType(TokenTypes.LITERAL_CHAR);
      }
    }
    return list;
  }

  private static int indexOfQuote(Segment text, char quote) {
    int i = 0;
    while (i < text.count) {
      char c = text.array[text.offset + i];
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == quote) {
        return i;
      }
      ++i;
    }
    return -1;
  }

  private static char quoteOfType(int type) {
    return type == TokenTypes.ERROR_CHAR ? '\'' : '"';
  }

  private static char markerQuote(int marker) {
    return (marker & SINGLE_QUOTE) != 0 ? '\'' : '"';
  }

  private static boolean isMarker(int value) {
    return (value & MARKER_MASK) == MARKER;
  }

  private static int languageOf(int marker) {
    return (marker >>> LANGUAGE_SHIFT) & 0xF;
  }

  private static int baseOf(int marker) {
    return (short) (marker & BASE_MASK);
  }

  private static int marker(char quote, int language, int base) {
    return MARKER
        | (quote == '\'' ? SINGLE_QUOTE : 0)
        | ((language & 0xF) << LANGUAGE_SHIFT)
        | (base & BASE_MASK);
  }

  @Override
  public void addNullToken() {
    this.delegate.addNullToken();
  }

  @Override
  public void addToken(char[] array, int start, int end, int tokenType, int startOffset) {
    this.delegate.addToken(array, start, end, tokenType, startOffset);
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
package org.zaproxy.zap.extension.prettyview.ui;

import java.util.Set;
import javax.swing.Action;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.OccurrenceMarker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenImpl;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Wraps every token maker the delegate supplies so that absurdly long lines never get lexed.
 *
 * <p>The editor re-tokenizes the whole logical line a painted row belongs to, so the cost of a
 * repaint is set by the longest line rather than the part of it on screen. Lexing tens of
 * thousands of characters on every scroll is what reads as scroll lag, and the token heap for
 * millions of tokens is what eats memory. A line past {@link #MAX_LEX_LINE_CHARS} that starts
 * fresh is therefore handed over as one identifier token instead: no lexing, one token, same
 * characters, and it paints as plain text. An identifier (rather than a null token) keeps every
 * token traversal - bracket matching included - on its ordinary path. Everything else tokenizes
 * exactly as the delegate would.
 *
 * <p>Only fresh lines bail: a line reached from inside a multiline construct (a block comment or
 * string spilling over from above, i.e. anything but {@link TokenTypes#NULL}) still lexes
 * normally, so constructs spanning lines keep their colouring on both sides.
 */
final class BoundedTokenMakerFactory extends TokenMakerFactory {

  private final TokenMakerFactory delegate;

  BoundedTokenMakerFactory(TokenMakerFactory delegate) {
    this.delegate = delegate;
  }

  @Override
  protected TokenMaker getTokenMakerImpl(String key) {
    TokenMaker maker = this.delegate.getTokenMaker(key);
    return maker == null ? null : new BoundedTokenMaker(maker);
  }

  @Override
  public Set<String> keySet() {
    return this.delegate.keySet();
  }

  private static final class BoundedTokenMaker implements TokenMaker {

    /**
     * Longest line still lexed. Aligns with {@link LargePayloadPolicy#MAX_SYNTAX_LINE_CHARS}: one
     * number, one story - longer lines render plain instead of stalling the editor.
     */
    static final int MAX_LEX_LINE_CHARS = LargePayloadPolicy.MAX_SYNTAX_LINE_CHARS;

    private final TokenMaker delegate;

    BoundedTokenMaker(TokenMaker delegate) {
      this.delegate = delegate;
    }

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
      if (initialTokenType == TokenTypes.NULL
          && text != null
          && text.count > MAX_LEX_LINE_CHARS) {
        return new TokenImpl(
            text.array, text.offset, text.offset + text.count - 1, startOffset,
            TokenTypes.IDENTIFIER, 0);
      }
      return this.delegate.getTokenList(text, initialTokenType, startOffset);
    }

    @Override
    public int getLastTokenTypeOnLine(Segment text, int initialTokenType) {
      if (initialTokenType == TokenTypes.NULL
          && text != null
          && text.count > MAX_LEX_LINE_CHARS) {
        return TokenTypes.NULL;
      }
      return this.delegate.getLastTokenTypeOnLine(text, initialTokenType);
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
}

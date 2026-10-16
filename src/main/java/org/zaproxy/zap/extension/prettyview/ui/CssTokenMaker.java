package org.zaproxy.zap.extension.prettyview.ui;

import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;

/**
 * Colours CSS the way Burp's Pretty does: selectors orange, ids gold, properties orange,
 * values grey, punctuation pale blue, strings green, numbers blue, comments muted.
 *
 * <p>Each line stands alone apart from two carried facts - whether a block comment is open and
 * how deep in braces the line starts - packed into the continuation state, so a monster line
 * still costs exactly one line of lexing. Selector versus declaration context comes from the
 * brace depth: words outside any rule are selectors, words opening a declaration are
 * properties, the rest are values.
 */
final class CssTokenMaker extends TokenMakerBase {

  /** Token types by Burp role; the scheme paints each in Burp's own hue. */
  private static final int SELECTOR = Token.RESERVED_WORD_2;
  private static final int ID = Token.FUNCTION;
  private static final int PROPERTY = Token.RESERVED_WORD;
  private static final int PUNCT = Token.OPERATOR;
  private static final int BRACE = Token.SEPARATOR;
  private static final int VALUE = Token.IDENTIFIER;
  private static final int ATRULE = Token.ANNOTATION;
  private static final int STRING = Token.LITERAL_STRING_DOUBLE_QUOTE;
  private static final int NUMBER = Token.LITERAL_NUMBER_DECIMAL_INT;
  private static final int COMMENT = Token.COMMENT_MULTILINE;

  @Override
  public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
    this.resetTokenList();
    char[] array = text.array;
    int offset = text.offset;
    int end = offset + text.count;
    int depth = initialTokenType >>> 1;
    if ((initialTokenType & 1) != 0) {
      int close = CssTokenMaker.indexOfCommentEnd(array, offset, end);
      if (close < 0) {
        this.addToken(text, offset, end - 1, COMMENT, startOffset);
        this.addNullToken();
        return this.firstToken;
      }
      this.addToken(text, offset, close + 1, COMMENT, startOffset);
      offset = close + 2;
      if (offset >= end) {
        this.addNullToken();
        return this.firstToken;
      }
    }
    boolean expectProperty = true;
    int i = offset;
    while (i < end) {
      char c = array[i];
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
        int j = i + 1;
        while (j < end && (array[j] == ' ' || array[j] == '\t')) {
          ++j;
        }
        this.addToken(text, i, j - 1, Token.WHITESPACE, startOffset + i - offset);
        i = j;
        continue;
      }
      if (c == '/' && i + 1 < end && array[i + 1] == '*') {
        int close = CssTokenMaker.indexOfCommentEnd(array, i + 2, end);
        if (close < 0) {
          this.addToken(text, i, end - 1, COMMENT, startOffset + i - offset);
          i = end;
          continue;
        }
        this.addToken(text, i, close + 1, COMMENT, startOffset + i - offset);
        i = close + 2;
        continue;
      }
      if (c == '"' || c == '\'') {
        int j = CssTokenMaker.indexOfStringEnd(array, i, end, c);
        this.addToken(text, i, j, STRING, startOffset + i - offset);
        i = j + 1;
        continue;
      }
      if (c == '{') {
        this.addToken(text, i, i, BRACE, startOffset + i - offset);
        ++depth;
        expectProperty = true;
        ++i;
        continue;
      }
      if (c == '}') {
        this.addToken(text, i, i, BRACE, startOffset + i - offset);
        depth = Math.max(0, depth - 1);
        expectProperty = true;
        ++i;
        continue;
      }
      if (c == ';') {
        this.addToken(text, i, i, PUNCT, startOffset + i - offset);
        expectProperty = true;
        ++i;
        continue;
      }
      if (c == ':') {
        if (depth == 0 && i + 1 < end
            && (array[i + 1] == ':' || CssTokenMaker.isNameStart(array[i + 1]))) {
          int j = i + (array[i + 1] == ':' ? 2 : 1);
          while (j < end && CssTokenMaker.isNameChar(array[j])) {
            ++j;
          }
          if (j < end && array[j] == '(') {
            ++j;
          }
          this.addToken(text, i, j - 1, PROPERTY, startOffset + i - offset);
          i = j;
          continue;
        }
        this.addToken(text, i, i, PUNCT, startOffset + i - offset);
        expectProperty = false;
        ++i;
        continue;
      }
      if (c == '@') {
        int j = i + 1;
        while (j < end && CssTokenMaker.isNameChar(array[j])) {
          ++j;
        }
        this.addToken(text, i, j - 1, ATRULE, startOffset + i - offset);
        i = j;
        continue;
      }
      if (c == '#') {
        int hex = CssTokenMaker.hexRun(array, i + 1, end);
        if (hex == 3 || hex == 4 || hex == 6 || hex == 8) {
          int after = i + 1 + hex;
          if (after >= end || !CssTokenMaker.isNameChar(array[after])) {
            this.addToken(text, i, after - 1, NUMBER, startOffset + i - offset);
            i = after;
            continue;
          }
        }
        int j = i + 1;
        while (j < end && CssTokenMaker.isNameChar(array[j])) {
          ++j;
        }
        this.addToken(text, i, j - 1, ID, startOffset + i - offset);
        i = j;
        continue;
      }
      if (c == '.' && i + 1 < end && CssTokenMaker.isNameStart(array[i + 1])) {
        int j = i + 2;
        while (j < end && CssTokenMaker.isNameChar(array[j])) {
          ++j;
        }
        this.addToken(text, i, j - 1, SELECTOR, startOffset + i - offset);
        i = j;
        continue;
      }
      if (c == '(' || c == ')' || c == '[' || c == ']' || c == ','
          || c == '>' || c == '+' || c == '~' || c == '/' || c == '=' || c == '!') {
        this.addToken(text, i, i, PUNCT, startOffset + i - offset);
        ++i;
        continue;
      }
      if (c == '*' && depth == 0) {
        this.addToken(text, i, i, SELECTOR, startOffset + i - offset);
        ++i;
        continue;
      }
      if (CssTokenMaker.isNameStart(c) || c >= 0x80) {
        int j = i + 1;
        while (j < end && (CssTokenMaker.isNameChar(array[j]) || array[j] >= 0x80)) {
          ++j;
        }
        boolean function = j < end && array[j] == '(';
        int type;
        if (depth == 0) {
          type = SELECTOR;
        } else if (function) {
          type = ID;
        } else if (expectProperty) {
          type = PROPERTY;
        } else {
          type = VALUE;
        }
        this.addToken(text, i, j - 1, type, startOffset + i - offset);
        if (function) {
          expectProperty = false;
        } else if (depth > 0 && expectProperty) {
          expectProperty = false;
        }
        i = j;
        continue;
      }
      if (c >= '0' && c <= '9'
          || c == '.' && i + 1 < end && array[i + 1] >= '0' && array[i + 1] <= '9'
          || c == '-' && i + 1 < end
              && (array[i + 1] >= '0' && array[i + 1] <= '9' || array[i + 1] == '.')) {
        int j = CssTokenMaker.indexOfNumberEnd(array, i, end);
        this.addToken(text, i, j - 1, NUMBER, startOffset + i - offset);
        int k = j;
        while (k < end && CssTokenMaker.isNameChar(array[k])) {
          ++k;
        }
        if (k > j) {
          this.addToken(text, j, k - 1, VALUE, startOffset + j - offset);
        }
        expectProperty = false;
        i = k;
        continue;
      }
      this.addToken(text, i, i, PUNCT, startOffset + i - offset);
      ++i;
    }
    this.addNullToken();
    return this.firstToken;
  }

  @Override
  public int getLastTokenTypeOnLine(Segment text, int initialTokenType) {
    char[] array = text.array;
    int offset = text.offset;
    int end = offset + text.count;
    int depth = initialTokenType >>> 1;
    boolean comment = (initialTokenType & 1) != 0;
    int i = offset;
    if (comment) {
      int close = CssTokenMaker.indexOfCommentEnd(array, i, end);
      if (close < 0) {
        return initialTokenType;
      }
      i = close + 2;
      comment = false;
    }
    while (i < end) {
      char c = array[i];
      if (c == '/' && i + 1 < end && array[i + 1] == '*') {
        int close = CssTokenMaker.indexOfCommentEnd(array, i + 2, end);
        if (close < 0) {
          comment = true;
          break;
        }
        i = close + 2;
        continue;
      }
      if (c == '"' || c == '\'') {
        i = CssTokenMaker.indexOfStringEnd(array, i, end, c) + 1;
        continue;
      }
      if (c == '{') {
        depth = Math.min(depth + 1, 0x3FFFFFFF);
      } else if (c == '}') {
        depth = Math.max(0, depth - 1);
      } else if (c == '\\') {
        ++i;
      }
      ++i;
    }
    return (depth << 1) | (comment ? 1 : 0);
  }

  @Override
  public boolean getCurlyBracesDenoteCodeBlocks(int languageIndex) {
    return true;
  }

  @Override
  public String[] getLineCommentStartAndEnd(int languageIndex) {
    return new String[] {"/*", "*/"};
  }

  private static boolean isNameStart(char c) {
    return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || c == '-';
  }

  private static boolean isNameChar(char c) {
    return CssTokenMaker.isNameStart(c) || c >= '0' && c <= '9';
  }

  private static int hexRun(char[] array, int from, int end) {
    int i = from;
    while (i < end) {
      char c = array[i];
      if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')) {
        break;
      }
      ++i;
    }
    return i - from;
  }

  private static int indexOfCommentEnd(char[] array, int from, int end) {
    for (int i = from; i + 1 < end; ++i) {
      if (array[i] == '*' && array[i + 1] == '/') {
        return i;
      }
    }
    return -1;
  }

  private static int indexOfStringEnd(char[] array, int from, int end, char quote) {
    int i = from + 1;
    while (i < end) {
      char c = array[i];
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == quote) {
        return i;
      }
      ++i;
    }
    return end - 1;
  }

  private static int indexOfNumberEnd(char[] array, int from, int end) {
    int i = from;
    if (i < end && (array[i] == '-' || array[i] == '+')) {
      ++i;
    }
    while (i < end && array[i] >= '0' && array[i] <= '9') {
      ++i;
    }
    if (i < end && array[i] == '.') {
      ++i;
      while (i < end && array[i] >= '0' && array[i] <= '9') {
        ++i;
      }
    }
    return i;
  }
}

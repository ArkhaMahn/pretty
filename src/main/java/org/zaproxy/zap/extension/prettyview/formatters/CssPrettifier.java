package org.zaproxy.zap.extension.prettyview.formatters;

public class CssPrettifier
implements PrettyPrettifier {
  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.CSS;
  }

  @Override
  public String prettify(String body) {
    if (body == null || body.trim().isEmpty()) {
      return body == null ? "" : body;
    }
    return new Writer(body.length()).run(body);
  }

  private static final class Writer {
    private final StringBuilder out;
    private final StringBuilder line = new StringBuilder();
    private int indent;
    private char quote;
    private boolean inLineComment;
    private boolean inBlockComment;
    /** Depth of open "{", so a colon inside a declaration can be told from one in a selector. */
    private int blockDepth;
    /** Depth of open "(", so an at-rule or function argument colon can be told from a selector's. */
    private int parenDepth;
    /** Depth of the innermost open "url(" call, or -1 outside one. A URL is text, not CSS to format. */
    private int urlDepth = -1;

    Writer(int capacity) {
      this.out = new StringBuilder(Math.max(256, capacity + capacity / 4 + 64));
    }

    String run(String body) {
      int length = body.length();
      block11: for (int i = 0; i < length; ++i) {
        char next;
        char c = body.charAt(i);
        char c2 = next = i + 1 < length ? body.charAt(i + 1) : (char)'\u0000';
        if (this.inLineComment) {
          this.line.append(c);
          if (c != '\n') continue;
          this.newLine();
          this.inLineComment = false;
          continue;
        }
        if (this.inBlockComment) {
          this.line.append(c);
          if (c != '*' || next != '/') continue;
          this.line.append(next);
          ++i;
          this.inBlockComment = false;
          continue;
        }
        if (this.quote != '\u0000') {
          this.line.append(c);
          if (c == '\\' && next != '\u0000') {
            this.line.append(next);
            ++i;
            continue;
          }
          if (c != this.quote) continue;
          this.quote = '\u0000';
          continue;
        }
        switch (c) {
          case '/': {
            if (next == '/') {
              this.line.append(c).append(next);
              ++i;
              this.inLineComment = true;
              continue block11;
            }
            if (next == '*') {
              this.line.append(c).append(next);
              ++i;
              this.inBlockComment = true;
              continue block11;
            }
            this.line.append(c);
            continue block11;
          }
          case '\"': 
          case '\'': {
            this.line.append(c);
            this.quote = c;
            continue block11;
          }
          case '{': {
            this.trimTrailingSpaces();
            // 1TBS: the brace stays on the selector or at-rule that introduces it. An empty line means
            // there is nothing to attach to, so the brace opens the line on its own.
            if (this.line.length() > 0) {
              this.line.append(' ');
            }
            this.line.append(c);
            this.newLine();
            ++this.indent;
            ++this.blockDepth;
            continue block11;
          }
          case '}': {
            this.newLine();
            this.indent = Math.max(0, this.indent - 1);
            this.blockDepth = Math.max(0, this.blockDepth - 1);
            this.line.append(c);
            this.newLine();
            continue block11;
          }
          case ';': {
            this.trimTrailingSpaces();
            this.line.append(c);
            // Inside parens the semicolon is part of what is written there - the ";base64," of a data
            // URI - rather than the end of a declaration, so it leaves the line where it is.
            if (this.parenDepth == 0) {
              this.newLine();
            }
            continue block11;
          }
          case '(': 
          case ')': {
            if (c == '(') {
              ++this.parenDepth;
              if (this.opensUrlCall()) {
                this.urlDepth = this.parenDepth;
              }
            }
            else {
              this.parenDepth = Math.max(0, this.parenDepth - 1);
              if (this.urlDepth > this.parenDepth) {
                this.urlDepth = -1;
              }
            }
            this.line.append(c);
            continue block11;
          }
          case ':': {
            this.appendColon();
            continue block11;
          }
          case ',': {
            // A comma joins selectors, at-rule conditions and declaration values alike, and ends none
            // of them: only ";" and "}" close something, so the line carries on after a comma. Inside
            // parens it separates function arguments instead, where the call is left as it was written
            // - "url(data:image/png;base64,AAA)" must not gain a character the URL does not have.
            this.line.append(c);
            if (this.parenDepth == 0 && next != 0 && !Character.isWhitespace(next)) {
              this.line.append(' ');
            }
            continue block11;
          }
          case '\n': 
          case '\r': {
            this.newLine();
            continue block11;
          }
          case '\t': 
          case ' ': {
            if (this.line.length() <= 0 || Character.isWhitespace(this.line.charAt(this.line.length() - 1))) continue block11;
            this.line.append(' ');
            continue block11;
          }
          default: {
            this.line.append(c);
          }
        }
      }
      this.newLine();
      return this.out.toString();
    }

    /**
     * A colon is followed by a space only where CSS actually wants one - a declaration, or an
     * at-rule / function argument such as {@code (max-width:600px)}. In a selector the colon
     * introduces a pseudo-class, and {@code b: hover} is invalid CSS, so nothing is added there.
     */
    private void appendColon() {
      this.trimTrailingSpaces();
      // decide before the colon lands on the line, otherwise the name no longer looks like a name
      boolean doubleColon = this.line.length() > 0 && this.line.charAt(this.line.length() - 1) == ':';
      boolean separated = (this.parenDepth > 0 && !this.insideUrl())
          || this.blockDepth > 0 && isPropertyName(this.line);
      this.line.append(':');
      if (!doubleColon && separated) {
        this.line.append(' ');
      }
    }

    /**
     * True inside a {@code url(...)} call, where the text is the URL itself: {@code data:image/png} is
     * one token and "data: image/png" is a broken one, so nothing is added between its characters.
     */
    private boolean insideUrl() {
      return this.urlDepth > 0 && this.parenDepth >= this.urlDepth;
    }

    /** True when the line so far ends in "url", so the "(" about to be read opens a URL call. */
    private boolean opensUrlCall() {
      int end = this.line.length();
      while (end > 0 && Character.isWhitespace(this.line.charAt(end - 1))) {
        --end;
      }
      if (end < 3) {
        return false;
      }
      if (end > 3 && isIdentifierChar(this.line.charAt(end - 4))) {
        return false;
      }
      return (this.line.charAt(end - 3) | 32) == 'u'
          && (this.line.charAt(end - 2) | 32) == 'r'
          && (this.line.charAt(end - 1) | 32) == 'l';
    }

    private static boolean isIdentifierChar(char c) {
      return c == '-' || Character.isJavaIdentifierPart(c);
    }

    /** True when the line so far is just a property name, which is what precedes a declaration colon. */
    private static boolean isPropertyName(CharSequence line) {
      int start = 0;
      if (line.length() == 0 || line.charAt(0) != '-') {
        if (line.length() == 0 || !Character.isJavaIdentifierStart(line.charAt(0))) {
          return false;
        }
      }
      else {
        // a custom property is written "--name"
        start = line.length() > 1 && line.charAt(1) == '-' ? 2 : 0;
      }
      for (int i = start; i < line.length(); ++i) {
        char c = line.charAt(i);
        if (!Character.isJavaIdentifierPart(c) && c != '-') {
          return false;
        }
      }
      return true;
    }

    private void trimTrailingSpaces() {
      while (this.line.length() > 0 && Character.isWhitespace(this.line.charAt(this.line.length() - 1))) {
        this.line.setLength(this.line.length() - 1);
      }
    }

    private void newLine() {
      this.trimTrailingSpaces();
      if (this.line.length() == 0) {
        return;
      }
      for (int i = 0; i < this.indent; ++i) {
        this.out.append(PrettyPrettifier.INDENT);
      }
      this.out.append((CharSequence)this.line).append('\n');
      this.line.setLength(0);
    }
  }
}


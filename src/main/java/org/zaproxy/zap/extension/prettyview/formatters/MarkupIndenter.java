package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Indentation-only markup formatting shared by the HTML and XML prettifiers. The input is
 * tokenised into tags and text and laid out with newlines and indentation, but no byte is ever
 * added, removed, moved or rewritten: unclosed tags stay unclosed, fragments stay fragments,
 * entities, doctypes and attribute quoting are untouched. A tree-based serializer would instead
 * complete whatever it thinks is missing - exactly the rewriting this view must not do to
 * responses under analysis.
 */
final class MarkupIndenter {
  /** Formats a raw-text element body (scripts, stylesheets); byte-verbatim on any failure. */
  interface BlockFormatter {
    String format(String content) throws Exception;
  }

  /** Matches the closing tag of a raw-text element, case-insensitively. */
  private static final Pattern RAW_TEXT_CLOSE =
      Pattern.compile("</\\s*([A-Za-z][A-Za-z0-9:-]*)\\s*>", Pattern.CASE_INSENSITIVE);

  private MarkupIndenter() {
  }

  /**
   * Formats {@code body} by indentation alone.
   *
   * @param voidElements tag names that never take a closing tag (HTML void elements; empty for
   *     XML, where every element closes explicitly)
   * @param verbatimBlocks tag names whose content is captured verbatim up to the matching close
   * @param blockFormatters raw-text handlers by tag name; a present handler gets a best-effort
   *     whitespace-only reformat, anything else stays verbatim
   * @param rawIgnoresSelfClose whether raw-text elements ignore a self-closing slash ({@code
   *     <script/>} still opens a raw-text element in HTML parsing)
   * @param maxDepth deepest nesting that is still laid out; deeper input goes back verbatim so
   *     adversarial nesting cannot unfold into megabytes of padding
   */
  static String format(
      String body,
      Set<String> voidElements,
      Set<String> verbatimBlocks,
      Map<String, BlockFormatter> blockFormatters,
      boolean rawIgnoresSelfClose,
      int maxDepth)
      throws PrettificationException {
    if (body == null || body.trim().isEmpty()) {
      throw new PrettificationException("Empty markup payload");
    }
    List<Token> tokens =
        MarkupIndenter.tokenize(body, verbatimBlocks, blockFormatters.keySet(), rawIgnoresSelfClose);
    return MarkupIndenter.layout(tokens, voidElements, blockFormatters, maxDepth);
  }

  /** A tag, a special block (comment, doctype, processing instruction), or a text run. */
  private static final class Token {
    enum Kind {
      OPEN,
      CLOSE,
      SPECIAL,
      TEXT,
      RAW
    }

    final Kind kind;
    /** The tag name in lower case for OPEN/CLOSE/RAW, or an empty string otherwise. */
    final String name;
    /** The exact input bytes of this token. */
    final String raw;
    /** True for {@code <br/>}-style self-closing open tags. */
    final boolean selfClosing;

    Token(Kind kind, String name, String raw, boolean selfClosing) {
      this.kind = kind;
      this.name = name;
      this.raw = raw;
      this.selfClosing = selfClosing;
    }
  }

  private static List<Token> tokenize(
      String body, Set<String> verbatimBlocks, Set<String> formattedBlocks, boolean rawIgnoresSelfClose)
      throws PrettificationException {
    List<Token> tokens = new ArrayList<Token>();
    int length = body.length();
    int index = 0;
    StringBuilder text = new StringBuilder();
    while (index < length) {
      char c = body.charAt(index);
      if (c == '<' && MarkupIndenter.isTagStart(body, index)) {
        if (text.length() > 0) {
          tokens.add(new Token(Token.Kind.TEXT, "", text.toString(), false));
          text.setLength(0);
        }
        index = MarkupIndenter.readToken(
            body, index, tokens, verbatimBlocks, formattedBlocks, rawIgnoresSelfClose);
        continue;
      }
      text.append(c);
      ++index;
    }
    if (text.length() > 0) {
      tokens.add(new Token(Token.Kind.TEXT, "", text.toString(), false));
    }
    return tokens;
  }

  /** True when {@code body} holds something tag-like at {@code index} (which holds {@code '<'}). */
  private static boolean isTagStart(String body, int index) {
    if (index + 1 >= body.length()) {
      return false;
    }
    char next = body.charAt(index + 1);
    return Character.isLetter(next) || next == '/' || next == '!' || next == '?';
  }

  /**
   * Reads one token starting at {@code index} (which holds {@code '<'}), appends it to {@code
   * tokens}, and returns the index just past it. Anything that fails to parse as a tag leaves the
   * {@code '<'} to the text run: stray angle brackets are content, never markup.
   */
  private static int readToken(
      String body,
      int index,
      List<Token> tokens,
      Set<String> verbatimBlocks,
      Set<String> formattedBlocks,
      boolean rawIgnoresSelfClose) {
    int length = body.length();
    if (body.startsWith("<!--", index)) {
      int end = body.indexOf("-->", index + 4);
      int stop = end < 0 ? length : end + 3;
      tokens.add(new Token(Token.Kind.SPECIAL, "", body.substring(index, stop), false));
      return stop;
    }
    if (index + 1 < length && body.charAt(index + 1) == '/') {
      int close = MarkupIndenter.scanTag(body, index);
      if (close < 0) {
        return index;
      }
      String raw = body.substring(index, close);
      tokens.add(new Token(Token.Kind.CLOSE, MarkupIndenter.tagName(raw, true), raw, false));
      return close;
    }
    if (index + 1 < length && body.charAt(index + 1) == '!') {
      if (body.regionMatches(true, index + 2, "[CDATA[", 0, 7)) {
        int end = body.indexOf("]]>", index + 9);
        int stop = end < 0 ? length : end + 3;
        tokens.add(new Token(Token.Kind.SPECIAL, "", body.substring(index, stop), false));
        return stop;
      }
      int close = MarkupIndenter.scanTag(body, index);
      if (close < 0) {
        return index;
      }
      tokens.add(new Token(Token.Kind.SPECIAL, "", body.substring(index, close), false));
      return close;
    }
    if (index + 1 < length && body.charAt(index + 1) == '?') {
      int end = body.indexOf("?>", index + 2);
      int stop = end < 0 ? MarkupIndenter.scanTag(body, index) : end + 2;
      if (stop < 0) {
        return index;
      }
      tokens.add(new Token(Token.Kind.SPECIAL, "", body.substring(index, stop), false));
      return stop;
    }
    int close = MarkupIndenter.scanTag(body, index);
    if (close < 0) {
      return index;
    }
    String raw = body.substring(index, close);
    String name = MarkupIndenter.tagName(raw, false);
    boolean selfClosing = MarkupIndenter.isSelfClosing(raw);
    tokens.add(new Token(Token.Kind.OPEN, name, raw, selfClosing));
    int stop = close;
    // Raw-text elements swallow everything up to their matching close tag verbatim.
    boolean rawElement =
        !name.isEmpty() && (verbatimBlocks.contains(name) || formattedBlocks.contains(name));
    if (rawElement && (rawIgnoresSelfClose || !selfClosing)) {
      int contentEnd = MarkupIndenter.findRawTextEnd(body, close, name);
      if (contentEnd > close) {
        tokens.add(new Token(Token.Kind.RAW, name, body.substring(close, contentEnd), false));
        stop = contentEnd;
      }
    }
    return stop;
  }

  /**
   * Index just past the {@code '>'} ending the tag at {@code index}, honouring quoted attribute
   * values, or -1 when the tag never terminates.
   */
  private static int scanTag(String body, int index) {
    int length = body.length();
    int i = index + 1;
    char quote = 0;
    while (i < length) {
      char c = body.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == '>') {
        return i + 1;
      }
      ++i;
    }
    return -1;
  }

  /** Tag name in lower case from a raw tag, or an empty string when there is none. */
  private static String tagName(String raw, boolean closing) {
    int i = closing ? 2 : 1;
    int length = raw.length();
    while (i < length && Character.isWhitespace(raw.charAt(i))) {
      ++i;
    }
    int start = i;
    while (i < length
        && (Character.isLetterOrDigit(raw.charAt(i))
            || raw.charAt(i) == ':'
            || raw.charAt(i) == '-')) {
      ++i;
    }
    if (start >= length || i == start || !Character.isLetter(raw.charAt(start))) {
      return "";
    }
    return raw.substring(start, i).toLowerCase(Locale.ROOT);
  }

  /** True for {@code <br/>}-style tags whose slash directly precedes the closing bracket. */
  private static boolean isSelfClosing(String raw) {
    int i = raw.length() - 2;
    while (i >= 0 && Character.isWhitespace(raw.charAt(i))) {
      --i;
    }
    return i >= 0 && raw.charAt(i) == '/';
  }

  /**
   * Index where the raw-text content of {@code element} ends (the start of its matching close
   * tag), or -1 when no closer exists, in which case the rest of the input is content.
   */
  private static int findRawTextEnd(String body, int from, String element) {
    Matcher matcher = RAW_TEXT_CLOSE.matcher(body);
    matcher.region(from, body.length());
    while (matcher.find()) {
      if (element.equals(matcher.group(1).toLowerCase(Locale.ROOT))) {
        return matcher.start();
      }
    }
    return -1;
  }

  private static String layout(
      List<Token> tokens,
      Set<String> voidElements,
      Map<String, BlockFormatter> blockFormatters,
      int maxDepth)
      throws PrettificationException {
    StringBuilder out = new StringBuilder();
    int indent = 0;
    int size = tokens.size();
    for (int t = 0; t < size; ++t) {
      Token token = tokens.get(t);
      switch (token.kind) {
        case SPECIAL:
          MarkupIndenter.emitMultiline(out, token.raw, indent);
          break;
        case TEXT:
          if (!token.raw.trim().isEmpty()) {
            MarkupIndenter.emitText(out, token.raw, indent);
          }
          break;
        case CLOSE:
          indent = Math.max(0, indent - 1);
          MarkupIndenter.emitLine(out, token.raw, indent);
          break;
        case OPEN:
          if (token.selfClosing
              || voidElements.contains(token.name)
              || MarkupIndenter.closesImmediately(tokens, t)) {
            MarkupIndenter.emitLine(out, token.raw, indent);
            break;
          }
          MarkupIndenter.emitLine(out, token.raw, indent);
          ++indent;
          if (indent > maxDepth) {
            throw new PrettificationException("Markup nests too deeply; rendered verbatim");
          }
          break;
        case RAW:
          MarkupIndenter.emitRaw(out, token, blockFormatters);
          break;
        default:
          MarkupIndenter.emitLine(out, token.raw, indent);
          break;
      }
      if (out.length() > PrettyPrettifier.MAX_OUTPUT_CHARS) {
        throw new PrettificationException("Formatted output exceeded its size cap; rendered verbatim");
      }
    }
    return out.toString();
  }

  /**
   * True when the next token closes this opening tag. Such pairs stay on one line instead of
   * unfolding an empty element across two.
   */
  private static boolean closesImmediately(List<Token> tokens, int openAt) {
    if (openAt + 1 >= tokens.size()) {
      return false;
    }
    Token open = tokens.get(openAt);
    Token next = tokens.get(openAt + 1);
    return next.kind == Token.Kind.CLOSE
        && !open.name.isEmpty()
        && open.name.equals(next.name);
  }

  private static void emitLine(StringBuilder out, String text, int indent) {
    if (out.length() > 0) {
      out.append('\n');
    }
    out.append(PrettyPrettifier.INDENT.repeat(Math.min(indent, PrettyPrettifier.MAX_INDENT_DEPTH)));
    out.append(text);
  }

  /** Emits text as it stands: the first line takes the indent, continuation lines are verbatim. */
  private static void emitText(StringBuilder out, String text, int indent) {
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; ++i) {
      if (i == 0) {
        MarkupIndenter.emitLine(out, lines[i], indent);
      } else {
        out.append('\n').append(lines[i]);
      }
    }
  }

  /** Emits a multi-line special block the same way: first line indented, the rest verbatim. */
  private static void emitMultiline(StringBuilder out, String text, int indent) {
    MarkupIndenter.emitText(out, text, indent);
  }

  /**
   * Emits raw-text element content byte for byte. A registered block formatter gets a best-effort
   * whitespace-only reformat (verbatim on any failure); anything else is never touched.
   */
  private static void emitRaw(
      StringBuilder out, Token token, Map<String, BlockFormatter> blockFormatters)
      throws PrettificationException {
    String content = token.raw;
    BlockFormatter formatter = blockFormatters.get(token.name);
    if (formatter != null && !content.trim().isEmpty()) {
      try {
        content = formatter.format(content);
        // The sub-formatters frame their output with a trailing newline of their own; drop it
        // so no blank line appears before the closing tag. Verbatim content is untouched.
        int end = content.length();
        while (end > 0 && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r')) {
          --end;
        }
        content = content.substring(0, end);
      } catch (Exception e) {
        content = token.raw;
      }
    }
    String[] lines = content.split("\n", -1);
    for (String line : lines) {
      if (out.length() > 0) {
        out.append('\n');
      }
      out.append(line);
    }
  }
}

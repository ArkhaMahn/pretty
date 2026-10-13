package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Indentation-only HTML formatting. The input is tokenised into tags and text and laid out with
 * newlines and indentation, but no byte is ever added, removed, moved or rewritten: unclosed tags
 * stay unclosed, fragments stay fragments, entities and attribute quoting are untouched. A
 * tree-based serializer (e.g. jsoup) would instead inject {@code html/head/body} wrappers, close
 * whatever it thinks is missing and normalise the markup - exactly the rewriting this view must
 * not do to incomplete responses under analysis.
 */
public class HtmlPrettifier
implements PrettyPrettifier {
  /** Elements that never take a closing tag, so they leave the indent level alone. */
  private static final Set<String> VOID_ELEMENTS = new HashSet<String>();
  /**
   * Elements whose content is captured verbatim up to the matching close tag instead of being
   * tokenised: markup-looking bytes inside scripts, styles and preformatted text are content.
   */
  private static final Set<String> RAW_TEXT_ELEMENTS = new HashSet<String>();
  /** Matches the closing tag of a raw-text element, case-insensitively. */
  private static final Pattern RAW_TEXT_CLOSE = Pattern.compile("</\\s*([A-Za-z][A-Za-z0-9:-]*)\\s*>", Pattern.CASE_INSENSITIVE);

  static {
    String[] voids = {
      "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
      "source", "track", "wbr"
    };
    for (String tag : voids) {
      VOID_ELEMENTS.add(tag);
    }
    String[] raw = {"script", "style", "textarea", "pre", "template", "title"};
    for (String tag : raw) {
      RAW_TEXT_ELEMENTS.add(tag);
    }
  }

  private final JavaScriptPrettifier jsPrettifier = new JavaScriptPrettifier();
  private final CssPrettifier cssPrettifier = new CssPrettifier();

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.HTML;
  }

  @Override
  public String prettify(String body) throws PrettificationException {
    if (body == null || body.trim().isEmpty()) {
      throw new PrettificationException("Empty HTML payload");
    }
    List<Token> tokens = HtmlPrettifier.tokenize(body);
    return HtmlPrettifier.layout(tokens, this);
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

  private static List<Token> tokenize(String body) throws PrettificationException {
    List<Token> tokens = new ArrayList<Token>();
    int length = body.length();
    int index = 0;
    StringBuilder text = new StringBuilder();
    while (index < length) {
      char c = body.charAt(index);
      if (c == '<' && HtmlPrettifier.isTagStart(body, index)) {
        if (text.length() > 0) {
          tokens.add(new Token(Token.Kind.TEXT, "", text.toString(), false));
          text.setLength(0);
        }
        int after = HtmlPrettifier.readToken(body, index, tokens);
        index = after;
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
  private static int readToken(String body, int index, List<Token> tokens)
      throws PrettificationException {
    int length = body.length();
    if (body.startsWith("<!--", index)) {
      int end = body.indexOf("-->", index + 4);
      int stop = end < 0 ? length : end + 3;
      tokens.add(new Token(Token.Kind.SPECIAL, "", body.substring(index, stop), false));
      return stop;
    }
    if (index + 1 < length && body.charAt(index + 1) == '/') {
      int close = HtmlPrettifier.scanTag(body, index);
      if (close < 0) {
        return index;
      }
      String raw = body.substring(index, close);
      String name = HtmlPrettifier.tagName(raw, true);
      tokens.add(new Token(Token.Kind.CLOSE, name, raw, false));
      return close;
    }
    if (index + 1 < length && body.charAt(index + 1) == '!') {
      if (body.regionMatches(true, index + 2, "[CDATA[", 0, 7)) {
        int end = body.indexOf("]]>", index + 9);
        int stop = end < 0 ? length : end + 3;
        tokens.add(new Token(Token.Kind.SPECIAL, "", body.substring(index, stop), false));
        return stop;
      }
      int close = HtmlPrettifier.scanTag(body, index);
      if (close < 0) {
        return index;
      }
      tokens.add(new Token(Token.Kind.SPECIAL, "", body.substring(index, close), false));
      return close;
    }
    if (index + 1 < length && body.charAt(index + 1) == '?') {
      int end = body.indexOf("?>", index + 2);
      int stop = end < 0 ? HtmlPrettifier.scanTag(body, index) : end + 2;
      if (stop < 0) {
        return index;
      }
      tokens.add(new Token(Token.Kind.SPECIAL, "", body.substring(index, stop), false));
      return stop;
    }
    int close = HtmlPrettifier.scanTag(body, index);
    if (close < 0) {
      return index;
    }
    String raw = body.substring(index, close);
    String name = HtmlPrettifier.tagName(raw, false);
    boolean selfClosing = HtmlPrettifier.isSelfClosing(raw);
    Token.Kind kind = Token.Kind.OPEN;
    tokens.add(new Token(kind, name, raw, selfClosing));
    int stop = close;
    // Raw-text elements swallow everything up to their matching close tag verbatim. The
    // self-closing slash never applies to them: in HTML parsing {@code <script/>} still opens
    // a raw-text element, it does not close one.
    if (!name.isEmpty() && RAW_TEXT_ELEMENTS.contains(name)) {
      int contentEnd = HtmlPrettifier.findRawTextEnd(body, close, name);
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

  private static String layout(List<Token> tokens, HtmlPrettifier owner)
      throws PrettificationException {
    StringBuilder out = new StringBuilder();
    int indent = 0;
    int size = tokens.size();
    for (int t = 0; t < size; ++t) {
      Token token = tokens.get(t);
      switch (token.kind) {
        case SPECIAL:
          HtmlPrettifier.emitMultiline(out, token.raw, indent);
          break;
        case TEXT:
          if (!token.raw.trim().isEmpty()) {
            HtmlPrettifier.emitText(out, token.raw, indent);
          }
          break;
        case CLOSE:
          indent = Math.max(0, indent - 1);
          HtmlPrettifier.emitLine(out, token.raw, indent);
          break;
        case OPEN:
          if (token.selfClosing
              || VOID_ELEMENTS.contains(token.name)
              || HtmlPrettifier.closesImmediately(tokens, t)) {
            HtmlPrettifier.emitLine(out, token.raw, indent);
            break;
          }
          HtmlPrettifier.emitLine(out, token.raw, indent);
          ++indent;
          if (indent > PrettyPrettifier.MAX_INDENT_DEPTH) {
            throw new PrettificationException("Markup nests too deeply; rendered verbatim");
          }
          break;
        case RAW:
          HtmlPrettifier.emitRaw(out, token, owner);
          break;
        default:
          HtmlPrettifier.emitLine(out, token.raw, indent);
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
        HtmlPrettifier.emitLine(out, lines[i], indent);
      } else {
        out.append('\n').append(lines[i]);
      }
    }
  }

  /** Emits a multi-line special block the same way: first line indented, the rest verbatim. */
  private static void emitMultiline(StringBuilder out, String text, int indent) {
    HtmlPrettifier.emitText(out, text, indent);
  }

  /**
   * Emits raw-text element content byte for byte. Embedded scripts and stylesheets still get a
   * best-effort whitespace-only reformat (verbatim on any failure); preformatted content is never
   * touched.
   */
  private static void emitRaw(StringBuilder out, Token token, HtmlPrettifier owner)
      throws PrettificationException {
    String content = token.raw;
    if (("script".equals(token.name) || "style".equals(token.name)) && !content.trim().isEmpty()) {
      try {
        content = "script".equals(token.name)
            ? owner.jsPrettifier.prettify(content)
            : owner.cssPrettifier.prettify(content);
        // The sub-formatters frame their output with a trailing newline of their own; drop it
        // so no blank line appears before the closing tag. Verbatim content below is untouched.
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

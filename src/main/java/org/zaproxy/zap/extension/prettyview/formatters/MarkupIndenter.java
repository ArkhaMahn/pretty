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
 *
 * <p>Layout obeys inline and block rules: block elements break onto their own lines and indent
 * their children, while inline elements and text flow together untouched ({@code hi <b>there</b>}
 * stays one line). XML passes an empty inline set, so every element lays out as a block.
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
   * @param inlineElements tag names that flow with surrounding text instead of breaking onto
   *     their own lines (HTML phrasing content; empty for XML, where every element is a block)
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
      Set<String> inlineElements,
      Set<String> verbatimBlocks,
      Map<String, BlockFormatter> blockFormatters,
      boolean rawIgnoresSelfClose,
      int maxDepth,
      boolean strict)
      throws PrettificationException {
    if (body == null || body.trim().isEmpty()) {
      throw new PrettificationException("Empty markup payload");
    }
    List<Token> tokens =
        MarkupIndenter.tokenize(body, verbatimBlocks, blockFormatters.keySet(), rawIgnoresSelfClose);
    return MarkupIndenter.layout(tokens, voidElements, inlineElements, blockFormatters, maxDepth, strict);
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

  /**
   * An output line under construction: finished lines plus at most one open line that inline
   * content keeps flowing into. Only newlines and indentation are ever inserted; token bytes
   * are appended untouched.
   */
  private static final class Flow {
    final List<String> lines = new ArrayList<String>();
    StringBuilder cur;
    int indent;
    int length;

    String indentOf() {
      return PrettyPrettifier.INDENT.repeat(Math.min(this.indent, PrettyPrettifier.MAX_INDENT_DEPTH));
    }

    /** Appends to the open line, starting an indented one when between lines. */
    void appendFlow(String segment) {
      if (segment.isEmpty()) {
        return;
      }
      if (this.cur == null) {
        // Whitespace between blocks carries no content; inside a line it separates words. At a
        // line start the indentation comes from the level, so source indentation is stripped -
        // without this a second pass over pretty output would indent the first pass's own
        // indentation again, and the layout could never settle.
        int start = 0;
        while (start < segment.length() && Character.isWhitespace(segment.charAt(start))) {
          ++start;
        }
        if (start >= segment.length()) {
          return;
        }
        this.cur = new StringBuilder(this.indentOf()).append(segment, start, segment.length());
        this.length += this.indentOf().length() + segment.length() - start;
      } else {
        this.cur.append(segment);
        this.length += segment.length();
      }
    }

    void flush() {
      if (this.cur != null) {
        this.lines.add(this.cur.toString());
        this.cur = null;
      }
    }

    /** A block of its own on a fresh indented line. */
    void ownLine(String text) {
      this.flush();
      this.lines.add(this.indentOf() + text);
      this.length += text.length();
    }

    /**
     * A content line at its block's level: non-blank lines take the current indent on top of
     * whatever relative indentation they carry, so nested content reads at its depth; blank
     * lines stay truly empty (no trailing spaces).
     */
    void contentLine(String text) {
      this.flush();
      if (text.isEmpty()) {
        this.lines.add(text);
      } else {
        String indented = this.indentOf() + text;
        this.lines.add(indented);
        this.length += this.indentOf().length();
      }
      this.length += text.length();
    }

    /**
     * Flows text inline: the first segment joins the open line, continuation lines sit at the
     * block's level, and a non-blank tail stays open so following inline content continues it.
     */
    void flowText(String text) {
      String[] segments = text.split("\n", -1);
      this.appendFlow(segments[0]);
      for (int i = 1; i < segments.length; ++i) {
        // Source indentation dies with its newline: the level re-indents continuation lines, so
        // keeping it would double-indent pretty output on a second pass. appendFlow strips the
        // line-start case; the strip here covers continuations, which never open the line.
        String segment = segments[i];
        int start = 0;
        while (start < segment.length() && Character.isWhitespace(segment.charAt(start))) {
          ++start;
        }
        String stripped = start >= segment.length() ? "" : segment.substring(start);
        this.flush();
        if (i == segments.length - 1) {
          if (!stripped.isEmpty()) {
            this.cur = new StringBuilder(this.indentOf()).append(stripped);
            this.length += this.indentOf().length() + stripped.length();
          }
        } else if (stripped.isEmpty()) {
          this.lines.add(stripped);
        } else {
          String indented = this.indentOf() + stripped;
          this.lines.add(indented);
          this.length += indented.length();
        }
      }
    }

    String join() {
      this.flush();
      StringBuilder out = new StringBuilder(this.length + this.lines.size());
      for (int i = 0; i < this.lines.size(); ++i) {
        if (i > 0) {
          out.append('\n');
        }
        out.append(this.lines.get(i));
      }
      return out.toString();
    }
  }

  private static String layout(
      List<Token> tokens,
      Set<String> voidElements,
      Set<String> inlineElements,
      Map<String, BlockFormatter> blockFormatters,
      int maxDepth,
      boolean strict)
      throws PrettificationException {
    Flow flow = new Flow();
    java.util.Deque<String> openElements = strict ? new java.util.ArrayDeque<String>() : null;
    int size = tokens.size();
    for (int t = 0; t < size; ++t) {
      Token token = tokens.get(t);
      switch (token.kind) {
        case SPECIAL:
          if (MarkupIndenter.isBlockSpecial(token.raw)) {
            flow.ownLine(token.raw);
          } else {
            flow.flowText(token.raw);
          }
          break;
        case TEXT:
          if (strict && openElements.isEmpty() && !token.raw.trim().isEmpty()) {
            throw new PrettificationException("Text outside the root element; rendered verbatim");
          }
          flow.flowText(token.raw);
          break;
        case CLOSE:
          if (strict) {
            if (openElements.isEmpty() || !openElements.pop().equals(token.name)) {
              throw new PrettificationException("Mismatched close tag; rendered verbatim");
            }
          }
          if (!token.name.isEmpty() && inlineElements.contains(token.name)) {
            flow.appendFlow(token.raw);
          } else {
            flow.flush();
            flow.indent = Math.max(0, flow.indent - 1);
            flow.ownLine(token.raw);
          }
          break;
        case OPEN:
          if (!token.name.isEmpty() && inlineElements.contains(token.name)) {
            flow.appendFlow(token.raw);
            break;
          }
          if (voidElements.contains(token.name)) {
            // A void element outside the inline set (link, meta, ...) carries no phrasing
            // content, so it lays out as a block on its own line instead of gluing to whatever
            // inline flow is open (a head of stylesheet links would otherwise read as one long
            // line). Inline-set voids flowed above and are untouched.
            flow.flush();
            flow.ownLine(token.raw);
            break;
          }
          if (token.selfClosing) {
            flow.appendFlow(token.raw);
            break;
          }
          if (MarkupIndenter.closesImmediately(tokens, t)) {
            flow.flush();
            flow.ownLine(token.raw + tokens.get(t + 1).raw);
            ++t;
            break;
          }
          int inlineClose =
              MarkupIndenter.collapsibleClose(tokens, t, voidElements, inlineElements);
          if (inlineClose > 0) {
            flow.flush();
            StringBuilder single = new StringBuilder(token.raw);
            for (int j = t + 1; j <= inlineClose; ++j) {
              single.append(tokens.get(j).raw);
            }
            flow.ownLine(single.toString());
            t = inlineClose;
            break;
          }
          if (strict && !token.name.isEmpty()) {
            openElements.push(token.name);
          }
          flow.flush();
          flow.ownLine(token.raw);
          ++flow.indent;
          if (flow.indent > maxDepth) {
            throw new PrettificationException("Markup nests too deeply; rendered verbatim");
          }
          break;
        case RAW:
          flow.flush();
          MarkupIndenter.emitRaw(flow, token, blockFormatters);
          break;
        default:
          flow.flush();
          flow.ownLine(token.raw);
          break;
      }
      if (flow.length > PrettyPrettifier.MAX_OUTPUT_CHARS) {
        throw new PrettificationException("Formatted output exceeded its size cap; rendered verbatim");
      }
    }
    if (strict && !openElements.isEmpty()) {
      throw new PrettificationException("Unclosed element; rendered verbatim");
    }
    return flow.join();
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

  /** Longest inline-only span, in characters, that still collapses onto one line. */
  private static final int COLLAPSE_MAX_CHARS = 1000;
  /** Furthest ahead the collapse scan looks for the matching close tag. */
  private static final int COLLAPSE_MAX_TOKENS = 300;

  /**
   * Index of the close tag completing a block whose whole content is inline (text, inline tags,
   * inline voids, single-line comments), so the element stays on one line, or -1 when the content
   * holds a nested block, a block-level void, raw content, a multi-line run, or no matching close
   * at all. Only single-line runs collapse: joining across a newline would delete it.
   */
  private static int collapsibleClose(
      List<Token> tokens, int openAt, Set<String> voidElements, Set<String> inlineElements) {
    Token open = tokens.get(openAt);
    if (open.name.isEmpty() || open.raw.indexOf('\n') >= 0) {
      return -1;
    }
    int total = 0;
    int size = tokens.size();
    for (int j = openAt + 1; j < size && j - openAt <= COLLAPSE_MAX_TOKENS; ++j) {
      Token current = tokens.get(j);
      switch (current.kind) {
        case TEXT:
        case SPECIAL:
          if (current.raw.indexOf('\n') >= 0) {
            return -1;
          }
          total += current.raw.length();
          break;
        case OPEN:
          if (!current.name.isEmpty() && inlineElements.contains(current.name)
              || current.selfClosing && !voidElements.contains(current.name)) {
            if (current.raw.indexOf('\n') >= 0) {
              return -1;
            }
            total += current.raw.length();
            break;
          }
          // A block-level void inside means the span is not inline-only after all.
          return -1;
        case CLOSE:
          if (!current.name.isEmpty() && inlineElements.contains(current.name)) {
            if (current.raw.indexOf('\n') >= 0) {
              return -1;
            }
            total += current.raw.length();
            break;
          }
          if (!current.name.equals(open.name)) {
            return -1;
          }
          total += current.raw.length();
          return total <= COLLAPSE_MAX_CHARS ? j : -1;
        default:
          return -1;
      }
      if (total > COLLAPSE_MAX_CHARS) {
        return -1;
      }
    }
    return -1;
  }

  /**
   * True for special blocks that stand on their own line (doctypes, processing instructions).
   * Comments flow inline with surrounding text instead.
   */
  private static boolean isBlockSpecial(String raw) {
    if (raw.startsWith("<?")) {
      return true;
    }
    return raw.startsWith("<!")
        && !raw.startsWith("<!--")
        && !raw.regionMatches(true, 2, "[CDATA[", 0, 7);
  }

  /**
   * Emits raw-text element content byte for byte. A registered block formatter gets a best-effort
   * whitespace-only reformat (verbatim on any failure); anything else is never touched.
   */
  private static void emitRaw(
      Flow flow, Token token, Map<String, BlockFormatter> blockFormatters)
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
      flow.contentLine(line);
    }
  }
}

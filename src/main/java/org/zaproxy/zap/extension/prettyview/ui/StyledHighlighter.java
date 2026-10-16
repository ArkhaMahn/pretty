package org.zaproxy.zap.extension.prettyview.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import org.zaproxy.zap.extension.prettyview.detect.PayloadFormat;

/**
 * Syntax highlighting for the Pretty editor: small line scanners with explicit carry state,
 * painted as character attributes on a styled document.
 *
 * <p>Every rule is linear - negated character classes and literal alternations, never nested
 * quantifiers - so attacker-sized lines cannot backtrack the editor. Multi-line constructs
 * (block comments, CDATA sections, template literals) carry a small state object from line to
 * line instead of matching across newlines. Re-highlighting a document repaints attributes
 * only; the text, caret and undo history are untouched, so callers suppress undo around it.
 */
final class StyledHighlighter {

  /** String or number or keyword token kinds a rule can paint. */
  enum Kind {
    COMMENT,
    STRING,
    NUMBER,
    KEYWORD,
    NAME,
    PUNCTUATION
  }

  /** Carry state across lines for multi-line constructs. */
  static final class State {
    boolean inBlockComment;
    boolean inHtmlComment;
    boolean inCdata;
    boolean inTemplate;
    int templateDepth;
    boolean inScript;
    boolean scriptIsJson;
    boolean inStyle;
  }

  private final AttributeSet base;
  private final AttributeSet comment;
  private final AttributeSet string;
  private final AttributeSet number;
  private final AttributeSet keyword;
  private final AttributeSet name;

  private StyledHighlighter(AttributeSet base, AttributeSet comment, AttributeSet string,
      AttributeSet number, AttributeSet keyword, AttributeSet name) {
    this.base = base;
    this.comment = comment;
    this.string = string;
    this.number = number;
    this.keyword = keyword;
    this.name = name;
  }

  /** Builds the styles for a palette: plain body text plus one attribute set per token kind. */
  static StyledHighlighter styles(EditorTheme.Palette palette) {
    return new StyledHighlighter(
        StyledHighlighter.text(palette.getForeground(), null),
        StyledHighlighter.text(new Color(106, 153, 85), null),
        StyledHighlighter.text(new Color(152, 195, 121), null),
        StyledHighlighter.text(new Color(97, 175, 239), null),
        StyledHighlighter.text(new Color(209, 154, 102), null),
        StyledHighlighter.text(new Color(156, 220, 254), null));
  }

  private static SimpleAttributeSet text(Color foreground, Color background) {
    SimpleAttributeSet attributes = new SimpleAttributeSet();
    if (foreground != null) {
      StyleConstants.setForeground(attributes, foreground);
    }
    if (background != null) {
      StyleConstants.setBackground(attributes, background);
    }
    return attributes;
  }

  private AttributeSet styleFor(Kind kind) {
    switch (kind) {
      case COMMENT:
        return this.comment;
      case STRING:
        return this.string;
      case NUMBER:
        return this.number;
      case KEYWORD:
        return this.keyword;
      case NAME:
        return this.name;
      case PUNCTUATION:
      default:
        return this.base;
    }
  }

  /**
   * Paints the document for {@code format} from line {@code fromLine} (0-based), scanning the
   * lines above only for carry state. Header lines keep the default colour; only the body
   * highlights. Linear in the document size; callers run it off the EDT for large payloads
   * and suppress undo around it. Returns false when {@code stillWanted} went false partway,
   * in which case the document is half-painted and the caller drops it.
   */
  boolean highlight(DefaultStyledDocument document, PayloadFormat format, int fromLine,
      java.util.function.BooleanSupplier stillWanted)
      throws BadLocationException {
    Element root = document.getDefaultRootElement();
    State state = new State();
    for (int line = 0; line < root.getElementCount(); ++line) {
      if ((line & 255) == 0 && !stillWanted.getAsBoolean()) {
        return false;
      }
      Element element = root.getElement(line);
      int start = element.getStartOffset();
      int end = Math.min(element.getEndOffset(), document.getLength());
      int length = Math.max(0, end - start);
      if (length <= 0) {
        continue;
      }
      String text = document.getText(start, length);
      if (line < fromLine) {
        this.scanLine(text, format, state);
      } else {
        this.paintLine(document, start, text, format, state);
      }
    }
    return stillWanted.getAsBoolean();
  }

  /**
   * Advances the carry state over a line without painting it, so an incremental repaint can
   * rebuild multi-line state forward from anywhere.
   */
  void scanLine(String text, PayloadFormat format, State state) {
    this.collect(text, format, state, new ArrayList<Span>());
  }

  /**
   * Paints one line, advancing the carry state for the next. The line is reset to body text
   * first so edited lines shed stale colours.
   */
  void paintLine(DefaultStyledDocument document, int offset, String text,
      PayloadFormat format, State state) {
    List<Span> spans = new ArrayList<>();
    this.collect(text, format, state, spans);
    document.setCharacterAttributes(offset, text.length(), this.base, true);
    for (Span span : spans) {
      document.setCharacterAttributes(offset + span.start, span.length,
          this.styleFor(span.kind), true);
    }
  }

  /** Collects the painted runs of one line into {@code spans}. */
  private void collect(String text, PayloadFormat format, State state, List<Span> spans) {
    switch (format == null ? PayloadFormat.PLAIN_TEXT : format) {
      case JSON:
        JsonRule.paint(text, state, spans);
        break;
      case HTML:
        MarkupRule.paint(text, true, state, spans);
        break;
      case XML:
        MarkupRule.paint(text, false, state, spans);
        break;
      case CSS:
        CssRule.paint(text, state, spans);
        break;
      case JAVASCRIPT:
        JavaScriptRule.paint(text, state, spans);
        break;
      default:
        break;
    }
  }

  /** A painted run: characters {@code [start, start + length)} in one token kind. */
  private static final class Span {
    final int start;
    final int length;
    final Kind kind;

    Span(int start, int length, Kind kind) {
      this.start = start;
      this.length = length;
      this.kind = kind;
    }
  }

  /** JSON: strings (keys when followed by a colon), numbers, booleans and null. */
  private static final class JsonRule {
    private static final Pattern TOKEN = Pattern.compile(
        "\"(?:[^\"\\\\\\r\\n]|\\\\.)*\"|-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b|\\b(?:true|false|null)\\b");

    static void paint(String text, State state, List<Span> spans) {
      Matcher matcher = TOKEN.matcher(text);
      while (matcher.find()) {
        String token = matcher.group();
        Kind kind;
        if (token.charAt(0) == '"') {
          int end = matcher.end();
          int colon = end;
          while (colon < text.length() && Character.isWhitespace(text.charAt(colon))) {
            ++colon;
          }
          kind = colon < text.length() && text.charAt(colon) == ':' ? Kind.KEYWORD : Kind.STRING;
        } else if (token.charAt(0) == '-' || Character.isDigit(token.charAt(0))) {
          kind = Kind.NUMBER;
        } else {
          kind = Kind.KEYWORD;
        }
        spans.add(new Span(matcher.start(), matcher.end() - matcher.start(), kind));
      }
    }
  }

  /**
   * HTML and XML tags: comments, CDATA sections, processing instructions and embedded
   * script/style blocks carry across lines; single-line tags are split into brackets, names,
   * attribute names and quoted values; entities colour even in text.
   */
  private static final class MarkupRule {
    private static final Pattern ATTR = Pattern.compile(
        "[A-Za-z_:][A-Za-z0-9_.:-]*|\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'");
    private static final Pattern ENTITY =
        Pattern.compile("&(?:[A-Za-z][A-Za-z0-9]*|#[0-9]+|#[xX][0-9a-fA-F]+);");
    private static final Pattern SCRIPT_TYPE =
        Pattern.compile("\\btype\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
            Pattern.CASE_INSENSITIVE);

    static void paint(String text, boolean html, State state, List<Span> spans) {
      int i = 0;
      int length = text.length();
      if (state.inScript) {
        i = MarkupRule.paintEmbedded(text, 0, false, state, spans);
        if (i >= length) {
          return;
        }
      } else if (state.inStyle) {
        i = MarkupRule.paintEmbedded(text, 0, true, state, spans);
        if (i >= length) {
          return;
        }
      }
      while (i < length) {
        if (state.inHtmlComment || state.inCdata) {
          String end = state.inHtmlComment ? "-->" : "]]>";
          int close = text.indexOf(end, i);
          if (close < 0) {
            spans.add(new Span(i, length - i, Kind.COMMENT));
            return;
          }
          spans.add(new Span(i, close + end.length() - i, Kind.COMMENT));
          i = close + end.length();
          state.inHtmlComment = false;
          state.inCdata = false;
          continue;
        }
        if (text.startsWith("<!--", i)) {
          int close = text.indexOf("-->", i + 4);
          if (close < 0) {
            spans.add(new Span(i, length - i, Kind.COMMENT));
            state.inHtmlComment = true;
            return;
          }
          spans.add(new Span(i, close + 3 - i, Kind.COMMENT));
          i = close + 3;
          continue;
        }
        if (!html && text.startsWith("<![CDATA[", i)) {
          int close = text.indexOf("]]>", i + 9);
          if (close < 0) {
            spans.add(new Span(i, length - i, Kind.COMMENT));
            state.inCdata = true;
            return;
          }
          spans.add(new Span(i, close + 3 - i, Kind.COMMENT));
          i = close + 3;
          continue;
        }
        if (!html && text.startsWith("<?", i)) {
          int close = text.indexOf("?>", i + 2);
          int run = close < 0 ? length - i : close + 2 - i;
          spans.add(new Span(i, run, Kind.COMMENT));
          i += run;
          continue;
        }
        char c = text.charAt(i);
        if (c == '<') {
          int end = MarkupRule.tagEnd(text, i + 1);
          if (end < 0 || end - i > 8000) {
            // Not a tag on this line (or a pathological one): leave the rest plain.
            return;
          }
          String tag = text.substring(i, end + 1);
          if (MarkupRule.isOpen(tag, "script")) {
            state.inScript = true;
            state.scriptIsJson = MarkupRule.scriptIsJson(tag);
          } else if (MarkupRule.isOpen(tag, "style")) {
            state.inStyle = true;
          } else if (MarkupRule.isClose(tag, "script")) {
            state.inScript = false;
          } else if (MarkupRule.isClose(tag, "style")) {
            state.inStyle = false;
          }
          MarkupRule.paintTag(text, i, end + 1, spans);
          Matcher entities = ENTITY.matcher(text);
          entities.region(i, end + 1);
          while (entities.find()) {
            spans.add(new Span(entities.start(), entities.end() - entities.start(), Kind.NAME));
          }
          i = end + 1;
          if (state.inScript || state.inStyle) {
            i = MarkupRule.paintEmbedded(text, i, state.inStyle, state, spans);
          }
          continue;
        }
        if (c == '&') {
          Matcher entity = ENTITY.matcher(text);
          entity.region(i, length);
          if (entity.lookingAt()) {
            spans.add(new Span(i, entity.end() - i, Kind.NAME));
            i = entity.end();
            continue;
          }
        }
        ++i;
      }
    }

    /** Index of the tag-closing bracket, skipping quoted attribute values. -1 if none. */
    private static int tagEnd(String text, int from) {
      int length = text.length();
      int i = from;
      while (i < length) {
        char c = text.charAt(i);
        if (c == '"' || c == '\'') {
          int close = text.indexOf(c, i + 1);
          if (close < 0 || close - i > 8000) {
            return -1;
          }
          i = close + 1;
          continue;
        }
        if (c == '>') {
          return i;
        }
        if (c == '<' || c == '\n' || c == '\r') {
          return -1;
        }
        ++i;
      }
      return -1;
    }

    private static boolean tagNameIs(String tag, boolean open, String name) {
      int i = open ? 1 : 2;
      int length = tag.length();
      while (i < length && Character.isWhitespace(tag.charAt(i))) {
        ++i;
      }
      if (i + name.length() > length) {
        return false;
      }
      for (int j = 0; j < name.length(); ++j) {
        if (Character.toLowerCase(tag.charAt(i + j)) != name.charAt(j)) {
          return false;
        }
      }
      int end = i + name.length();
      return end >= length || !Character.isLetterOrDigit(tag.charAt(end));
    }

    private static boolean isOpen(String tag, String name) {
      return !tag.startsWith("</") && MarkupRule.tagNameIs(tag, true, name);
    }

    private static boolean isClose(String tag, String name) {
      return tag.startsWith("</") && MarkupRule.tagNameIs(tag, false, name);
    }

    /** Whether a script block holds JSON rather than JavaScript (ld+json and friends). */
    private static boolean scriptIsJson(String tag) {
      Matcher type = SCRIPT_TYPE.matcher(tag);
      if (!type.find()) {
        return false;
      }
      String value = type.group(1) != null ? type.group(1)
          : type.group(2) != null ? type.group(2) : type.group(3);
      return value != null && value.toLowerCase(java.util.Locale.ROOT).contains("json");
    }

    /**
     * Paints embedded script or style content from {@code i} with the matching sub-lexer,
     * stopping at the closing tag. Returns the offset to continue markup scanning from: the
     * close tag itself, or the end of the line when the block carries on. The caller's
     * inScript/inStyle flags are left alone; the main loop clears them when it paints the
     * close tag.
     */
    private static int paintEmbedded(String text, int i, boolean style, State state,
        List<Span> spans) {
      String close = style ? "</style" : "</script";
      int length = text.length();
      int end = indexOfIgnoreCase(text, close, i);
      int run = end < 0 ? length : end;
      if (run > i) {
        List<Span> inner = new ArrayList<Span>();
        String content = text.substring(i, run);
        if (style) {
          CssRule.paint(content, state, inner);
        } else if (state.scriptIsJson) {
          JsonRule.paint(content, state, inner);
        } else {
          JavaScriptRule.paint(content, state, inner);
        }
        for (Span span : inner) {
          spans.add(new Span(span.start + i, span.length, span.kind));
        }
      }
      return end < 0 ? length : end;
    }

    private static int indexOfIgnoreCase(String text, String token, int from) {
      int max = text.length() - token.length();
      for (int i = Math.max(from, 0); i <= max; ++i) {
        if (text.regionMatches(true, i, token, 0, token.length())) {
          return i;
        }
      }
      return -1;
    }

    /** Brackets and names in keyword colour, attribute names lighter, values as strings. */
    private static void paintTag(String text, int from, int to, List<Span> spans) {
      Matcher matcher = ATTR.matcher(text);
      matcher.region(from, to);
      boolean first = true;
      while (matcher.find()) {
        String token = matcher.group();
        char c0 = token.charAt(0);
        Kind kind;
        if (c0 == '"' || c0 == '\'') {
          kind = Kind.STRING;
        } else if (first) {
          kind = Kind.KEYWORD;
          first = false;
        } else {
          kind = Kind.NAME;
        }
        spans.add(new Span(matcher.start(), matcher.end() - matcher.start(), kind));
      }
      // Brackets and the tag name region edges stay visible against the body text.
      spans.add(new Span(from, 1, Kind.KEYWORD));
      if (text.charAt(from + 1) == '/') {
        spans.add(new Span(from + 1, 1, Kind.KEYWORD));
      }
    }
  }

  /** CSS: comments carry, strings and numbers paint, rules stay body text. */
  private static final class CssRule {
    private static final Pattern TOKEN = Pattern.compile(
        "/\\*|\"(?:[^\"\\\\\\r\\n]|\\\\.)*\"|'(?:[^'\\\\\\r\\n]|\\\\.)*'|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?%?|#[0-9a-fA-F]{3,8}\\b|@[A-Za-z-]+|url(?=\\s*\\()",
        Pattern.CASE_INSENSITIVE);

    static void paint(String text, State state, List<Span> spans) {
      int i = 0;
      int length = text.length();
      if (state.inBlockComment) {
        int close = text.indexOf("*/", i);
        if (close < 0) {
          spans.add(new Span(0, length, Kind.COMMENT));
          return;
        }
        spans.add(new Span(0, close + 2, Kind.COMMENT));
        i = close + 2;
        state.inBlockComment = false;
      }
      Matcher matcher = TOKEN.matcher(text);
      matcher.region(i, length);
      while (matcher.find()) {
        String token = matcher.group();
        if (token.equals("/*")) {
          int close = text.indexOf("*/", matcher.end());
          if (close < 0) {
            spans.add(new Span(matcher.start(), length - matcher.start(), Kind.COMMENT));
            state.inBlockComment = true;
            return;
          }
          spans.add(new Span(matcher.start(), close + 2 - matcher.start(), Kind.COMMENT));
          matcher.region(close + 2, length);
          continue;
        }
        if (token.regionMatches(true, 0, "url", 0, 3)) {
          int open = matcher.end();
          while (open < length && Character.isWhitespace(text.charAt(open))) {
            ++open;
          }
          if (open < length && text.charAt(open) == '(') {
            int close = CssRule.balancedEnd(text, open);
            spans.add(new Span(matcher.start(), 3, Kind.KEYWORD));
            spans.add(new Span(matcher.start() + 3, close - matcher.start() - 3, Kind.STRING));
            matcher.region(close, length);
            continue;
          }
          spans.add(new Span(matcher.start(), matcher.end() - matcher.start(), Kind.KEYWORD));
          continue;
        }
        char c0 = token.charAt(0);
        Kind kind;
        if (c0 == '"' || c0 == '\'') {
          kind = Kind.STRING;
        } else if (c0 == '-' || Character.isDigit(c0) || c0 == '#') {
          kind = Kind.NUMBER;
        } else {
          kind = Kind.KEYWORD;
        }
        spans.add(new Span(matcher.start(), matcher.end() - matcher.start(), kind));
      }
    }

    /** Index past the balanced close paren from {@code open}, honouring quotes. */
    private static int balancedEnd(String text, int open) {
      int length = text.length();
      int depth = 0;
      int i = open;
      while (i < length) {
        char c = text.charAt(i);
        if (c == '"' || c == '\'') {
          i = CssRule.quotedEnd(text, i);
          continue;
        }
        if (c == '(') {
          ++depth;
        } else if (c == ')') {
          --depth;
          if (depth == 0) {
            return i + 1;
          }
        } else if ((c == ';' || c == '{' || c == '}') && depth == 1) {
          return i;
        }
        ++i;
      }
      return length;
    }

    /** Index past the quoted string starting at {@code quote}, or the line end. */
    private static int quotedEnd(String text, int quote) {
      int length = text.length();
      char mark = text.charAt(quote);
      int i = quote + 1;
      while (i < length) {
        char c = text.charAt(i);
        if (c == '\\') {
          i += 2;
          continue;
        }
        if (c == mark || c == '\r' || c == '\n') {
          return c == mark ? i + 1 : i;
        }
        ++i;
      }
      return length;
    }
  }

  /**
   * JavaScript: line and carried block comments, quoted strings, regex literals, carried
   * template literals with brace depth, numbers and keywords.
   */
  private static final class JavaScriptRule {
    private static final java.util.Set<String> KEYWORDS = new java.util.HashSet<String>(
        java.util.Arrays.asList(("break|case|catch|class|const|continue|debugger|default|delete|do|"
            + "else|export|extends|finally|for|function|if|import|in|instanceof|let|new|return|"
            + "super|switch|this|throw|try|typeof|var|void|while|with|yield|await|async|static|get|"
            + "set|of").split("\\|")));
    /** Keywords after which a slash opens a regex literal rather than dividing. */
    private static final java.util.Set<String> REGEX_KEYWORDS = new java.util.HashSet<String>(
        java.util.Arrays.asList(
            "return|typeof|case|do|else|in|of|new|delete|void|yield|await|throw".split("\\|")));

    static void paint(String text, State state, List<Span> spans) {
      int length = text.length();
      int i = 0;
      if (state.inBlockComment) {
        int close = text.indexOf("*/", 0);
        if (close < 0) {
          spans.add(new Span(0, length, Kind.COMMENT));
          return;
        }
        spans.add(new Span(0, close + 2, Kind.COMMENT));
        i = close + 2;
        state.inBlockComment = false;
      }
      if (state.inTemplate) {
        i = JavaScriptRule.paintTemplate(text, 0, state, spans);
        if (i >= length) {
          return;
        }
      }
      while (i < length) {
        char c = text.charAt(i);
        if (c == '/' && i + 1 < length && text.charAt(i + 1) == '/') {
          spans.add(new Span(i, length - i, Kind.COMMENT));
          return;
        }
        if (c == '/' && i + 1 < length && text.charAt(i + 1) == '*') {
          int close = text.indexOf("*/", i + 2);
          if (close < 0) {
            spans.add(new Span(i, length - i, Kind.COMMENT));
            state.inBlockComment = true;
            return;
          }
          spans.add(new Span(i, close + 2 - i, Kind.COMMENT));
          i = close + 2;
          continue;
        }
        if (c == '"' || c == '\'') {
          int end = JavaScriptRule.quotedEnd(text, i);
          spans.add(new Span(i, Math.min(end, length) - i, Kind.STRING));
          i = Math.min(end, length);
          continue;
        }
        if (c == '`') {
          i = JavaScriptRule.paintTemplate(text, i, state, spans);
          continue;
        }
        if (c == '/' && JavaScriptRule.isRegexStart(text, i)) {
          int end = JavaScriptRule.regexEnd(text, i);
          if (end > i) {
            spans.add(new Span(i, end - i, Kind.STRING));
            i = end;
            continue;
          }
        }
        if (c == '-' && i + 1 < length && Character.isDigit(text.charAt(i + 1))
            || Character.isDigit(c)) {
          int end = JavaScriptRule.numberEnd(text, i);
          spans.add(new Span(i, end - i, Kind.NUMBER));
          i = end;
          continue;
        }
        if (Character.isJavaIdentifierStart(c) || c == '$') {
          int end = i + 1;
          while (end < length
              && (Character.isJavaIdentifierPart(text.charAt(end)) || text.charAt(end) == '$')) {
            ++end;
          }
          if (KEYWORDS.contains(text.substring(i, end))) {
            spans.add(new Span(i, end - i, Kind.KEYWORD));
          }
          i = end;
          continue;
        }
        ++i;
      }
    }

    /** Index past a quoted string starting at the quote in {@code quote}, or the line end. */
    private static int quotedEnd(String text, int quote) {
      int length = text.length();
      int i = quote + 1;
      char mark = text.charAt(quote);
      while (i < length) {
        char c = text.charAt(i);
        if (c == '\\') {
          i += 2;
          continue;
        }
        if (c == mark || c == '\r' || c == '\n') {
          return c == mark ? i + 1 : i;
        }
        ++i;
      }
      return length;
    }

    /** Whether a slash at {@code slash} opens a regex literal rather than dividing. */
    private static boolean isRegexStart(String text, int slash) {
      int i = slash - 1;
      while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
        --i;
      }
      if (i < 0) {
        return true;
      }
      char c = text.charAt(i);
      if ("=([{,:;!&|?+-*%^~<>".indexOf(c) >= 0) {
        return true;
      }
      if (!Character.isJavaIdentifierPart(c) && c != '$') {
        return false;
      }
      int end = i + 1;
      int start = end;
      while (start > 0
          && (Character.isJavaIdentifierPart(text.charAt(start - 1)) || text.charAt(start - 1) == '$')) {
        --start;
      }
      return REGEX_KEYWORDS.contains(text.substring(start, end));
    }

    /**
     * Index past a regex literal starting at the slash in {@code slash}, flags included - or
     * {@code slash} itself when the body never closes on this line.
     */
    private static int regexEnd(String text, int slash) {
      int length = text.length();
      int i = slash + 1;
      boolean inClass = false;
      while (i < length) {
        char c = text.charAt(i);
        if (c == '\\') {
          i += 2;
          continue;
        }
        if (c == '[') {
          inClass = true;
        } else if (c == ']') {
          inClass = false;
        } else if (c == '/' && !inClass) {
          ++i;
          while (i < length && "gimsuy".indexOf(text.charAt(i)) >= 0) {
            ++i;
          }
          return i;
        } else if (c == '\r' || c == '\n') {
          return slash;
        }
        ++i;
      }
      return slash;
    }

    /** Index past a numeric literal starting at {@code start}: hex, binary, decimal, exponent. */
    private static int numberEnd(String text, int start) {
      int length = text.length();
      int i = start;
      if (text.charAt(i) == '-') {
        ++i;
      }
      if (i + 1 < length && text.charAt(i) == '0'
          && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X')) {
        i += 2;
        while (i < length && isHexDigit(text.charAt(i))) {
          ++i;
        }
        return i;
      }
      if (i + 1 < length && text.charAt(i) == '0'
          && (text.charAt(i + 1) == 'b' || text.charAt(i + 1) == 'B')) {
        i += 2;
        while (i < length && (text.charAt(i) == '0' || text.charAt(i) == '1')) {
          ++i;
        }
        return i;
      }
      while (i < length && Character.isDigit(text.charAt(i))) {
        ++i;
      }
      if (i < length && text.charAt(i) == '.' && i + 1 < length
          && Character.isDigit(text.charAt(i + 1))) {
        ++i;
        while (i < length && Character.isDigit(text.charAt(i))) {
          ++i;
        }
      }
      if (i < length && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
        int j = i + 1;
        if (j < length && (text.charAt(j) == '+' || text.charAt(j) == '-')) {
          ++j;
        }
        if (j < length && Character.isDigit(text.charAt(j))) {
          i = j;
          while (i < length && Character.isDigit(text.charAt(i))) {
            ++i;
          }
        }
      }
      return i;
    }

    private static boolean isHexDigit(char c) {
      return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Paints a template literal from the backtick at {@code from}, carrying state. */
    private static int paintTemplate(String text, int from, State state, List<Span> spans) {
      int length = text.length();
      int i = state.inTemplate ? from : from + 1;
      if (!state.inTemplate) {
        state.inTemplate = true;
        state.templateDepth = 0;
      }
      int run = from;
      while (i < length) {
        char c = text.charAt(i);
        if (c == '\\') {
          i += 2;
          continue;
        }
        if (c == '`' && state.templateDepth == 0) {
          spans.add(new Span(run, i + 1 - run, Kind.STRING));
          state.inTemplate = false;
          return i + 1;
        }
        if (c == '$' && i + 1 < length && text.charAt(i + 1) == '{') {
          state.templateDepth++;
          i += 2;
          continue;
        }
        if (c == '}' && state.templateDepth > 0) {
          state.templateDepth--;
          i++;
          continue;
        }
        if (c == '\r' || c == '\n') {
          break;
        }
        ++i;
      }
      spans.add(new Span(run, i - run, Kind.STRING));
      return i;
    }
  }
}

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
   * and suppress undo around it.
   */
  void highlight(DefaultStyledDocument document, PayloadFormat format, int fromLine)
      throws BadLocationException {
    Element root = document.getDefaultRootElement();
    State state = new State();
    for (int line = 0; line < root.getElementCount(); ++line) {
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
        "\"(?:[^\"\\\\\\r\\n]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|\\b(?:true|false|null)\\b");

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
   * HTML and XML tags: comments, CDATA sections and processing instructions carry across lines;
   * single-line tags are split into brackets, names, attribute names and quoted values.
   */
  private static final class MarkupRule {
    private static final Pattern TAG_OPEN = Pattern.compile("</?[A-Za-z][^<>\\r\\n]*>?");
    private static final Pattern ATTR = Pattern.compile(
        "[A-Za-z_:][A-Za-z0-9_.:-]*|\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'");
    private static final Pattern ENTITY = Pattern.compile("&[A-Za-z0-9#]+;");

    static void paint(String text, boolean html, State state, List<Span> spans) {
      int i = 0;
      int length = text.length();
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
          int end = text.indexOf('>', i + 1);
          if (end < 0 || end - i > 8000) {
            // Not a tag on this line (or a pathological one): leave the rest plain.
            return;
          }
          MarkupRule.paintTag(text, i, end + 1, spans);
          Matcher entities = ENTITY.matcher(text);
          entities.region(i, end + 1);
          while (entities.find()) {
            spans.add(new Span(entities.start(), entities.end() - entities.start(), Kind.NAME));
          }
          i = end + 1;
          continue;
        }
        ++i;
      }
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
        "/\\*|\"(?:[^\"\\\\\\r\\n]|\\\\.)*\"|'(?:[^'\\\\\\r\\n]|\\\\.)*'|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?%?|#[0-9a-fA-F]{3,8}\\b|@[A-Za-z-]+");

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
  }

  /**
   * JavaScript: line and carried block comments, quoted strings, carried template literals
   * with brace depth, numbers and keywords.
   */
  private static final class JavaScriptRule {
    private static final Pattern TOKEN = Pattern.compile(
        "//[^\r\n]*|`|\"(?:[^\"\\\\\\r\\n]|\\\\.)*\"|'(?:[^'\\\\\\r\\n]|\\\\.)*'|-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b|\\b(?:break|case|catch|class|const|continue|debugger|default|delete|do|else|export|extends|finally|for|function|if|import|in|instanceof|let|new|return|super|switch|this|throw|try|typeof|var|void|while|with|yield|await|async|static|get|set|of)\\b");

    static void paint(String text, State state, List<Span> spans) {
      int i = 0;
      int length = text.length();
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
      Matcher matcher = TOKEN.matcher(text);
      matcher.region(i, length);
      while (matcher.find()) {
        String token = matcher.group();
        if (token.startsWith("/*")) {
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
        if (token.equals("`")) {
          i = JavaScriptRule.paintTemplate(text, matcher.start(), state, spans);
          if (i >= length) {
            return;
          }
          matcher.region(i, length);
          continue;
        }
        char c0 = token.charAt(0);
        Kind kind;
        if (c0 == '/' || token.startsWith("//")) {
          kind = Kind.COMMENT;
        } else if (c0 == '"' || c0 == '\'') {
          kind = Kind.STRING;
        } else if (c0 == '-' || Character.isDigit(c0)) {
          kind = Kind.NUMBER;
        } else {
          kind = Kind.KEYWORD;
        }
        spans.add(new Span(matcher.start(), matcher.end() - matcher.start(), kind));
      }
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

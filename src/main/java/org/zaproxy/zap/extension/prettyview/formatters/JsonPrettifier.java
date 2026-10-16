package org.zaproxy.zap.extension.prettyview.formatters;

/**
 * Indentation-only JSON formatting. The input is scanned token by token - strings verbatim and
 * escape-aware, structural characters driving newlines and indentation, everything else copied -
 * but no byte is ever added, removed, moved or rewritten: numbers keep their spelling ({@code
 * 1e2} stays {@code 1e2}), escape sequences and key order survive untouched. A parse-and-serialize
 * round-trip would instead normalise all of that - exactly the rewriting this view must not do to
 * responses under analysis.
 *
 * <p>Malformed input goes back verbatim rather than laid out: a probe like {@code {"a":}} is
 * often deliberately broken, and indenting it would rewrite the bytes an editable view then
 * sends. Validation is structural only - values may repeat at top level, so multi-JSON streams
 * still lay out - but every value must be complete where the grammar demands one.
 */
public class JsonPrettifier
implements PrettyPrettifier {
  /** Deepest nesting that is still laid out; deeper input goes back verbatim. */
  private static final int MAX_DEPTH = 256;

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.JSON;
  }

  @Override
  public String prettify(String body) throws PrettificationException {
    if (body == null || body.trim().isEmpty()) {
      throw new PrettificationException("Empty JSON payload");
    }
    JsonPrettifier.validate(body);
    StringBuilder out = new StringBuilder(body.length() + 64);
    int indent = 0;
    int length = body.length();
    int i = 0;
    boolean lineHasContent = false;
    // An XSSI guard (Google's ")]}'" prefix) is not JSON: keep its line verbatim and lay out
    // only what follows, instead of exploding the guard across lines as stray closers.
    if (body.startsWith(")]}'")) {
      int newline = body.indexOf('\n');
      if (newline < 0) {
        return body;
      }
      out.append(body, 0, newline);
      i = newline + 1;
    }
    while (i < length) {
      char c = body.charAt(i);
      if (c == '"') {
        int end = JsonPrettifier.scanString(body, i);
        JsonPrettifier.separate(out, indent, lineHasContent);
        out.append(body, i, end);
        lineHasContent = true;
        i = end;
      } else if (c == '{' || c == '[') {
        if (JsonPrettifier.isEmptyPair(body, i)) {
          JsonPrettifier.separate(out, indent, lineHasContent);
          out.append(c).append(body.charAt(JsonPrettifier.nextNonWhitespace(body, i + 1)));
          lineHasContent = true;
          i = JsonPrettifier.nextNonWhitespace(body, i + 1) + 1;
          continue;
        }
        JsonPrettifier.separate(out, indent, lineHasContent);
        out.append(c);
        lineHasContent = false;
        ++indent;
        if (indent > MAX_DEPTH) {
          throw new PrettificationException("JSON nests too deeply; rendered verbatim");
        }
        ++i;
      } else if (c == '}' || c == ']') {
        indent = Math.max(0, indent - 1);
        JsonPrettifier.newline(out, indent);
        out.append(c);
        lineHasContent = true;
        ++i;
      } else if (c == ',') {
        out.append(c);
        lineHasContent = false;
        ++i;
      } else if (c == ':') {
        out.append(c).append(' ');
        ++i;
      } else if (Character.isWhitespace(c)) {
        ++i;
      } else {
        if (!lineHasContent) {
          JsonPrettifier.newline(out, indent);
        }
        out.append(c);
        lineHasContent = true;
        ++i;
      }
      if (out.length() > PrettyPrettifier.MAX_OUTPUT_CHARS) {
        throw new PrettificationException("Formatted output exceeded its size cap; rendered verbatim");
      }
    }
    return out.toString();
  }

  /**
   * Structural validation: every value must be complete where the grammar demands one, so
   * malformed input ({@code {"a":}}, {@code [1,]}, {@code {"a"}}) goes back verbatim instead of
   * laid out. Linear and iterative - no regex, no recursion - so hostile input cannot hurt it.
   * Layout stays exactly as lenient as before for anything that passes: numbers keep their
   * spelling, escapes and key order are untouched, and several top-level values in a row (a
   * multi-JSON stream) each validate on their own.
   */
  private static void validate(String body) throws PrettificationException {
    int length = body.length();
    int i = 0;
    if (body.startsWith(")]}'")) {
      int newline = body.indexOf('\n');
      if (newline < 0) {
        return;
      }
      i = newline + 1;
    }
    boolean sawValue = false;
    JsonPrettifier.checkDepth(body, i);
    while (true) {
      i = JsonPrettifier.skipWhitespace(body, i);
      if (i >= length) {
        if (!sawValue) {
          throw new PrettificationException("No JSON value; rendered verbatim");
        }
        return;
      }
      i = JsonPrettifier.skipValue(body, i, 0);
      sawValue = true;
    }
  }

  /**
   * Rejects input nesting past {@link #MAX_DEPTH} before validation starts. Validation recurses
   * once per nesting level, and a hundred thousand open brackets would otherwise recurse just as
   * deep; counting first bounds the recursion to the same ceiling the layout already enforces.
   * Strings are skipped escape-aware so a bracket inside one never counts.
   */
  private static void checkDepth(String body, int from) throws PrettificationException {
    int length = body.length();
    int depth = 0;
    boolean inString = false;
    int i = from;
    while (i < length) {
      char c = body.charAt(i);
      if (inString) {
        if (c == '\\') {
          i += 2;
          continue;
        }
        if (c == '"') {
          inString = false;
        }
        ++i;
        continue;
      }
      if (c == '"') {
        inString = true;
        ++i;
        continue;
      }
      if (c == '{' || c == '[') {
        if (++depth > MAX_DEPTH) {
          throw new PrettificationException("JSON nests too deeply; rendered verbatim");
        }
      } else if (c == '}' || c == ']') {
        --depth;
      }
      ++i;
    }
  }

  /** Index just past the JSON value starting at {@code index}, or throws when malformed. */
  private static int skipValue(String body, int index, int depth) throws PrettificationException {
    if (depth > MAX_DEPTH) {
      throw new PrettificationException("JSON nests too deeply; rendered verbatim");
    }
    int length = body.length();
    if (index >= length) {
      throw new PrettificationException("Missing JSON value; rendered verbatim");
    }
    char c = body.charAt(index);
    if (c == '{') {
      int i = JsonPrettifier.skipWhitespace(body, index + 1);
      if (i < length && body.charAt(i) == '}') {
        return i + 1;
      }
      while (true) {
        i = JsonPrettifier.skipString(body, i);
        i = JsonPrettifier.skipWhitespace(body, i);
        if (i >= length || body.charAt(i) != ':') {
          throw new PrettificationException("Missing JSON colon; rendered verbatim");
        }
        i = JsonPrettifier.skipValue(body, JsonPrettifier.skipWhitespace(body, i + 1), depth + 1);
        i = JsonPrettifier.skipWhitespace(body, i);
        if (i >= length) {
          throw new PrettificationException("Unterminated JSON object; rendered verbatim");
        }
        char next = body.charAt(i);
        if (next == '}') {
          return i + 1;
        }
        if (next != ',') {
          throw new PrettificationException("Missing JSON comma; rendered verbatim");
        }
        i = JsonPrettifier.skipWhitespace(body, i + 1);
      }
    }
    if (c == '[') {
      int i = JsonPrettifier.skipWhitespace(body, index + 1);
      if (i < length && body.charAt(i) == ']') {
        return i + 1;
      }
      while (true) {
        i = JsonPrettifier.skipValue(body, i, depth + 1);
        i = JsonPrettifier.skipWhitespace(body, i);
        if (i >= length) {
          throw new PrettificationException("Unterminated JSON array; rendered verbatim");
        }
        char next = body.charAt(i);
        if (next == ']') {
          return i + 1;
        }
        if (next != ',') {
          throw new PrettificationException("Missing JSON comma; rendered verbatim");
        }
        i = JsonPrettifier.skipWhitespace(body, i + 1);
      }
    }
    if (c == '"') {
      return JsonPrettifier.skipString(body, index);
    }
    if (c == 't' || c == 'f' || c == 'n') {
      return JsonPrettifier.skipLiteral(body, index);
    }
    if (c == '-' || c >= '0' && c <= '9') {
      return JsonPrettifier.skipNumber(body, index);
    }
    throw new PrettificationException("Missing JSON value; rendered verbatim");
  }

  private static int skipWhitespace(String body, int index) {
    int length = body.length();
    int i = index;
    while (i < length && Character.isWhitespace(body.charAt(i))) {
      ++i;
    }
    return i;
  }

  /** Index just past the string starting at {@code index}, validating escapes as it goes. */
  private static int skipString(String body, int index) throws PrettificationException {
    int length = body.length();
    if (index >= length || body.charAt(index) != '"') {
      throw new PrettificationException("Missing JSON string; rendered verbatim");
    }
    int i = index + 1;
    while (i < length) {
      char c = body.charAt(i);
      if (c == '"') {
        return i + 1;
      }
      if (c == '\\') {
        if (i + 1 >= length) {
          break;
        }
        char escaped = body.charAt(i + 1);
        if (escaped == 'u') {
          if (i + 5 >= length) {
            break;
          }
          for (int j = i + 2; j <= i + 5; ++j) {
            char hex = body.charAt(j);
            if (!(hex >= '0' && hex <= '9' || hex >= 'a' && hex <= 'f' || hex >= 'A' && hex <= 'F')) {
              throw new PrettificationException("Bad JSON escape; rendered verbatim");
            }
          }
          i += 6;
          continue;
        }
        if ("\"\\/bfnrt".indexOf(escaped) < 0) {
          throw new PrettificationException("Bad JSON escape; rendered verbatim");
        }
        i += 2;
        continue;
      }
      if (c < 0x20) {
        throw new PrettificationException("Bare control character in JSON string; rendered verbatim");
      }
      ++i;
    }
    throw new PrettificationException("Unterminated JSON string; rendered verbatim");
  }

  /** Index just past the {@code true}, {@code false} or {@code null} at {@code index}. */
  private static int skipLiteral(String body, int index) throws PrettificationException {
    String word;
    char c = body.charAt(index);
    if (c == 't') {
      word = "true";
    } else if (c == 'f') {
      word = "false";
    } else {
      word = "null";
    }
    int end = index + word.length();
    if (end > body.length() || !body.regionMatches(index, word, 0, word.length())) {
      throw new PrettificationException("Bad JSON literal; rendered verbatim");
    }
    if (end < body.length() && JsonPrettifier.isWordChar(body.charAt(end))) {
      throw new PrettificationException("Bad JSON literal; rendered verbatim");
    }
    return end;
  }

  /** Index just past the strictly-formed number starting at {@code index}. */
  private static int skipNumber(String body, int index) throws PrettificationException {
    int length = body.length();
    int i = index;
    if (i < length && body.charAt(i) == '-') {
      ++i;
    }
    if (i >= length) {
      throw new PrettificationException("Bad JSON number; rendered verbatim");
    }
    if (body.charAt(i) == '0') {
      ++i;
    } else if (body.charAt(i) >= '1' && body.charAt(i) <= '9') {
      do {
        ++i;
      } while (i < length && body.charAt(i) >= '0' && body.charAt(i) <= '9');
    } else {
      throw new PrettificationException("Bad JSON number; rendered verbatim");
    }
    if (i < length && body.charAt(i) == '.') {
      ++i;
      if (i >= length || !(body.charAt(i) >= '0' && body.charAt(i) <= '9')) {
        throw new PrettificationException("Bad JSON number; rendered verbatim");
      }
      do {
        ++i;
      } while (i < length && body.charAt(i) >= '0' && body.charAt(i) <= '9');
    }
    if (i < length && (body.charAt(i) == 'e' || body.charAt(i) == 'E')) {
      ++i;
      if (i < length && (body.charAt(i) == '+' || body.charAt(i) == '-')) {
        ++i;
      }
      if (i >= length || !(body.charAt(i) >= '0' && body.charAt(i) <= '9')) {
        throw new PrettificationException("Bad JSON number; rendered verbatim");
      }
      do {
        ++i;
      } while (i < length && body.charAt(i) >= '0' && body.charAt(i) <= '9');
    }
    if (i < length && JsonPrettifier.isWordChar(body.charAt(i))) {
      throw new PrettificationException("Bad JSON number; rendered verbatim");
    }
    return i;
  }

  private static boolean isWordChar(char c) {
    return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '$';
  }

  /**
   * Index just past the closing quote of the string starting at {@code index}, honouring
   * backslash escapes; the end of input when the string never terminates.
   */
  private static int scanString(String body, int index) {
    int length = body.length();
    int i = index + 1;
    while (i < length) {
      char c = body.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == '"') {
        return i + 1;
      }
      ++i;
    }
    return length;
  }

  /** Index of the next non-whitespace character at or after {@code index}, or the length. */
  private static int nextNonWhitespace(String body, int index) {
    int length = body.length();
    int i = index;
    while (i < length && Character.isWhitespace(body.charAt(i))) {
      ++i;
    }
    return i;
  }

  /** True when the bracket at {@code index} closes immediately ({@code {}} or {@code []}). */
  private static boolean isEmptyPair(String body, int index) {
    char open = body.charAt(index);
    int next = JsonPrettifier.nextNonWhitespace(body, index + 1);
    if (next >= body.length()) {
      return false;
    }
    char close = body.charAt(next);
    return open == '{' && close == '}' || open == '[' && close == ']';
  }

  private static void newline(StringBuilder out, int indent) {
    // Idempotent: a pending line break (left by the XSSI guard above) is not doubled.
    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
      out.append('\n');
    }
    out.append(PrettyPrettifier.INDENT.repeat(Math.min(indent, PrettyPrettifier.MAX_INDENT_DEPTH)));
  }

  /**
   * Separates the next value from whatever precedes it: a single space when the line already
   * carries content (unless one is there), otherwise a fresh indented line.
   */
  private static void separate(StringBuilder out, int indent, boolean lineHasContent) {
    if (lineHasContent) {
      if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
        out.append(' ');
      }
    } else {
      JsonPrettifier.newline(out, indent);
    }
  }
}

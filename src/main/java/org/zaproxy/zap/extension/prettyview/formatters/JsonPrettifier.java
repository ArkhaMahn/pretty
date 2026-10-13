package org.zaproxy.zap.extension.prettyview.formatters;

/**
 * Indentation-only JSON formatting. The input is scanned token by token - strings verbatim and
 * escape-aware, structural characters driving newlines and indentation, everything else copied -
 * but no byte is ever added, removed, moved or rewritten: numbers keep their spelling ({@code
 * 1e2} stays {@code 1e2}), escape sequences and key order survive untouched, and unbalanced input
 * is still laid out structurally instead of failing. A parse-and-reserialize round-trip would
 * instead normalise all of that - exactly the rewriting this view must not do to responses under
 * analysis.
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
    StringBuilder out = new StringBuilder(body.length() + 64);
    int indent = 0;
    int length = body.length();
    int i = 0;
    boolean lineHasContent = false;
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
    if (out.length() > 0) {
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

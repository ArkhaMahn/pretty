package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whitespace-only GraphQL formatting. JSON envelopes (including batch arrays) are laid out by
 * structure with every byte preserved; bare operations get brace-driven indentation. Nothing is
 * ever synthesised: no wrapper lines, no member comments, no re-serialization.
 */

public class GraphQlPrettifier
implements PrettyPrettifier {
  private static final Pattern STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"", 32);
  private static final String[] OPERATIONS = new String[]{"query", "mutation", "subscription", "fragment"};
  private final JsonPrettifier jsonPrettifier = new JsonPrettifier();

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.GRAPHQL;
  }

  @Override
  public String prettify(String body) throws PrettificationException {
    if (body == null || body.trim().isEmpty()) {
      throw new PrettificationException("Empty GraphQL payload");
    }
    String trimmed = body.trim();
    if (trimmed.charAt(0) == '[' || trimmed.charAt(0) == '{') {
      // A JSON envelope, a batch array, or an anonymous operation such as "{ hero { name } }":
      // all lay out structurally with every byte preserved, so no envelope surgery is needed.
      return this.jsonPrettifier.prettify(body);
    }
    return GraphQlPrettifier.formatOperation(trimmed);
  }

  private static String formatOperation(String operation) throws PrettificationException {
    StringBuilder out = new StringBuilder(operation.length() + operation.length() / 3 + 16);
    int length = operation.length();
    int depth = 0;
    int index = 0;
    Matcher strings = STRING.matcher(operation);
    while (index < length) {
      if (out.length() > PrettyPrettifier.MAX_OUTPUT_CHARS) {
        throw new PrettificationException(
            "GraphQL output exceeded its size cap; rendered verbatim");
      }
      char c = operation.charAt(index);
      if (c == '\"') {
        strings.region(index, length);
        if (strings.lookingAt()) {
          out.append(strings.group());
          index = strings.end();
          continue;
        }
      }
      switch (c) {
        case '{': {
          // 1TBS: the brace stays on the line that introduces it. When the previous token already
          // closed its own line - ")" of an argument list, or a comma - there is nothing to attach to
          // and the brace opens the line on its own.
          if (!GraphQlPrettifier.endsWithSpaceOrNewline(out)) {
            out.append(' ');
          }
          out.append(c);
          GraphQlPrettifier.newLine(out, ++depth);
          break;
        }
        case '(': 
        case '[': {
          out.append(c);
          GraphQlPrettifier.newLine(out, ++depth);
          break;
        }
        case ')': 
        case ']': 
        case '}': {
          GraphQlPrettifier.newLine(out, Math.max(0, depth - 1));
          depth = Math.max(0, depth - 1);
          out.append(c);
          break;
        }
        case ',': {
          out.append(c);
          GraphQlPrettifier.newLine(out, depth);
          break;
        }
        case '\n': 
        case '\r': {
          break;
        }
        case '\t': 
        case ' ': {
          if (out.length() <= 0 || GraphQlPrettifier.endsWithSpaceOrNewline(out)) break;
          out.append(' ');
          break;
        }
        default: {
          out.append(c);
        }
      }
      ++index;
    }
    return out.toString().trim();
  }

  private static boolean endsWithSpaceOrNewline(StringBuilder out) {
    return out.length() == 0 || Character.isWhitespace(out.charAt(out.length() - 1));
  }

  private static void newLine(StringBuilder out, int depth) {
    if (out.length() == 0) {
      return;
    }
    while (out.length() > 0 && Character.isWhitespace(out.charAt(out.length() - 1))) {
      out.setLength(out.length() - 1);
    }
    if (out.length() == 0 || out.charAt(out.length() - 1) == '\n') {
      return;
    }
    out.append('\n');
    int drawn = Math.min(depth, PrettyPrettifier.MAX_INDENT_DEPTH);
    for (int i = 0; i < drawn; ++i) {
      out.append(PrettyPrettifier.INDENT);
    }
  }

  public static boolean looksLikeGraphQl(String body) {
    if (body == null) {
      return false;
    }
    String lower = body.trim().toLowerCase(Locale.ROOT);
    for (String operation : OPERATIONS) {
      if (!lower.startsWith(operation + " ") && !lower.startsWith(operation + "{")) continue;
      return true;
    }
    return lower.startsWith("{") && lower.contains(")");
  }

  public static List<String> getOperations() {
    return List.of(OPERATIONS);
  }
}


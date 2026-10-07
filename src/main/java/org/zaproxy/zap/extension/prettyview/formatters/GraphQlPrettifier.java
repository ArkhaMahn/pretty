package org.zaproxy.zap.extension.prettyview.formatters;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    if (trimmed.charAt(0) == '[') {
      return this.formatEnvelope(GraphQlPrettifier.parse(trimmed), trimmed);
    }
    if (trimmed.charAt(0) == '{') {
      // A leading brace is either a JSON envelope or an anonymous operation such as "{ hero { name } }".
      // Only the former parses as JSON with a "query" member, so try that first and fall through to
      // the operation formatter instead of failing on a perfectly good anonymous query.
      JsonObject root = GraphQlPrettifier.parseObject(trimmed);
      if (root != null && GraphQlPrettifier.asString(root, "query") != null) {
        return this.formatEnvelope(root, trimmed);
      }
      return GraphQlPrettifier.formatOperation(trimmed);
    }
    return GraphQlPrettifier.formatOperation(trimmed);
  }

  private static JsonObject parseObject(String json) {
    try {
      JsonElement element = JsonParser.parseString(json);
      return element.isJsonObject() ? element.getAsJsonObject() : null;
    }
    catch (JsonParseException | IllegalStateException e) {
      return null;
    }
  }

  private static JsonElement parse(String json) throws PrettificationException {
    try {
      return JsonParser.parseString(json);
    }
    catch (JsonParseException | IllegalStateException e) {
      throw new PrettificationException("Malformed GraphQL JSON envelope", e);
    }
  }

  private String formatEnvelope(JsonElement element, String json) throws PrettificationException {
    if (!element.isJsonObject()) {
      return this.jsonPrettifier.prettify(json);
    }
    JsonObject root = element.getAsJsonObject();
    String query = GraphQlPrettifier.asString(root, "query");
    if (query == null) {
      return this.jsonPrettifier.prettify(json);
    }
    StringBuilder out = new StringBuilder();
    out.append("query\n{\n");
    out.append(GraphQlPrettifier.indent(GraphQlPrettifier.formatOperation(query)));
    out.append("}\n");
    ArrayList others = new ArrayList(root.keySet());
    others.remove("query");
    if (!others.isEmpty()) {
      out.append('\n').append("// remaining members: ").append(String.join((CharSequence)", ", others)).append('\n');
      root.remove("query");
      if (!root.isEmpty()) {
        out.append(this.jsonPrettifier.prettify(root.toString())).append('\n');
      }
    }
    return out.toString();
  }

  private static String asString(JsonObject root, String member) {
    JsonElement value = root.get(member);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      return null;
    }
    return value.getAsString();
  }

  private static String formatOperation(String operation) {
    StringBuilder out = new StringBuilder(operation.length() + operation.length() / 3 + 16);
    int length = operation.length();
    int depth = 0;
    int index = 0;
    Matcher strings = STRING.matcher(operation);
    while (index < length) {
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
    for (int i = 0; i < depth; ++i) {
      out.append(PrettyPrettifier.INDENT);
    }
  }

  private static String indent(String text) {
    StringBuilder out = new StringBuilder(text.length() + 32);
    for (String line : text.split("\n")) {
      out.append(PrettyPrettifier.INDENT).append(line).append('\n');
    }
    return out.toString();
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


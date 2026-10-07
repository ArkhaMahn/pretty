package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlPrettifier
implements PrettyPrettifier {
  private static final Set<String> CLAUSE_KEYWORDS = new HashSet<String>(Arrays.asList("SELECT", "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "ON", "UNION", "UNION ALL", "INTERSECT", "EXCEPT", "CREATE", "TABLE", "ALTER", "DROP", "INDEX", "VIEW", "TRIGGER", "PROCEDURE", "FUNCTION", "AND", "OR", "RETURNING", "WITH", "CASE", "WHEN", "THEN", "ELSE", "END", "BEGIN", "COMMIT", "ROLLBACK", "PRAGMA", "GRANT", "REVOKE"));
  private static final Set<String> LINE_BREAKING = new HashSet<String>(Arrays.asList("SELECT", "FROM", "WHERE", "HAVING", "ORDER", "LIMIT", "OFFSET", "INTO", "VALUES", "SET", "UNION", "UNION ALL", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "ON", "RETURNING"));
  private static final Pattern WHITESPACE_RUN = Pattern.compile("[\\s]+");

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.SQL;
  }

  @Override
  public String prettify(String body) {
    if (body == null || body.trim().isEmpty()) {
      return body == null ? "" : body;
    }
    String normalised = body.replace("\r\n", " ").replace('\n', ' ').replace('\t', ' ');
    StringBuilder out = new StringBuilder(normalised.length() + normalised.length() / 4 + 16);
    int depth = 0;
    int length = normalised.length();
    int index = 0;
    while (index < length) {
      int end;
      char c = normalised.charAt(index);
      if (c == '\'' || c == '\"' || c == '`') {
        index = SqlPrettifier.copyQuoted(out, normalised, index, c);
        continue;
      }
      if (c == '-' && index + 1 < length && normalised.charAt(index + 1) == '-') {
        end = normalised.indexOf(10, index);
        if (end < 0) {
          end = length;
        }
        out.append(normalised, index, end);
        index = end;
        continue;
      }
      if (c == '/' && index + 1 < length && normalised.charAt(index + 1) == '*') {
        end = normalised.indexOf("*/", index + 2);
        end = end < 0 ? length : end + 2;
        out.append(normalised, index, end);
        index = end;
        continue;
      }
      if (Character.isWhitespace(c)) {
        int next = SqlPrettifier.skipWhitespace(normalised, index);
        if (out.length() > 0 && !SqlPrettifier.endsWithSpaceOrNewline(out)) {
          out.append(' ');
        }
        index = next;
        continue;
      }
      int wordEnd = SqlPrettifier.endOfWord(normalised, index);
      String word = normalised.substring(index, wordEnd);
      String keyword = word.toUpperCase(Locale.ROOT);
      if (CLAUSE_KEYWORDS.contains(keyword)) {
        SqlPrettifier.appendKeyword(out, keyword, depth, LINE_BREAKING.contains(keyword));
      } else {
        if (out.length() > 0 && !SqlPrettifier.endsWithSpaceOrNewline(out)) {
          out.append(' ');
        }
        out.append(word);
      }
      index = wordEnd;
    }
    return out.toString().replaceAll("[ \\t]+\n", "\n").trim() + "\n";
  }

  private static void appendKeyword(StringBuilder out, String keyword, int depth, boolean newLine) {
    if (newLine && out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
      out.append('\n');
      for (int i = 0; i < depth; ++i) {
        out.append(PrettyPrettifier.INDENT);
      }
    } else if (out.length() > 0 && !SqlPrettifier.endsWithSpaceOrNewline(out)) {
      out.append(' ');
    }
    out.append(keyword);
    out.append(' ');
  }

  private static int copyQuoted(StringBuilder out, String text, int start, char quote) {
    out.append(text.charAt(start));
    int index = start + 1;
    int length = text.length();
    while (index < length) {
      char c = text.charAt(index);
      if (c == quote) {
        if (index + 1 < length && text.charAt(index + 1) == quote) {
          out.append(c).append(text.charAt(index + 1));
          index += 2;
          continue;
        }
        out.append(c);
        return index + 1;
      }
      out.append(c);
      ++index;
    }
    return length;
  }

  private static int skipWhitespace(String text, int start) {
    Matcher matcher = WHITESPACE_RUN.matcher(text);
    matcher.region(start, text.length());
    return matcher.lookingAt() ? matcher.end() : start + 1;
  }

  private static int endOfWord(String text, int start) {
    int index;
    for (index = start; index < text.length() && (Character.isLetterOrDigit(text.charAt(index)) || text.charAt(index) == '_'); ++index) {
    }
    return index == start ? start + 1 : index;
  }

  private static boolean endsWithSpaceOrNewline(StringBuilder out) {
    return out.length() == 0 || Character.isWhitespace(out.charAt(out.length() - 1));
  }
}


package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.ArrayList;
import java.util.List;

public class CsvPrettifier
implements PrettyPrettifier {
  private static final int MAX_CELL_WIDTH = 60;
  private static final int MAX_ROWS = 5000;
  private static final char[] DELIMITERS = new char[]{',', '\t', '|'};

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.CSV;
  }

  @Override
  public String prettify(String body) {
    if (body == null || body.trim().isEmpty()) {
      return body == null ? "" : body;
    }
    char delimiter = CsvPrettifier.detectDelimiter(body);
    List<String[]> rows = CsvPrettifier.parse(body, delimiter);
    if (rows.isEmpty()) {
      return body;
    }
    int columns = 0;
    for (String[] stringArray : rows) {
      columns = Math.max(columns, stringArray.length);
    }
    int[] widths = new int[columns];
    for (String[] row : rows) {
      for (int i = 0; i < row.length; ++i) {
        widths[i] = Math.max(widths[i], Math.min(row[i].length(), 60));
      }
    }
    StringBuilder stringBuilder = new StringBuilder(body.length() + body.length() / 3 + 64);
    int limit = Math.min(rows.size(), 5000);
    for (int r = 0; r < limit; ++r) {
      String[] row = rows.get(r);
      for (int c = 0; c < row.length; ++c) {
        if (c > 0) {
          stringBuilder.append(PrettyPrettifier.INDENT);
        }
        stringBuilder.append(row[c]);
        if (c >= row.length - 1) continue;
        CsvPrettifier.pad(stringBuilder, row[c].length(), widths[c]);
      }
      stringBuilder.append('\n');
    }
    if (rows.size() > limit) {
      stringBuilder.append("... ").append(rows.size() - limit).append(" further row(s) omitted\n");
    }
    return stringBuilder.toString();
  }

  private static char detectDelimiter(String body) {
    int best = 0;
    char bestDelimiter = ',';
    for (char candidate : DELIMITERS) {
      int count = CsvPrettifier.countOccurrences(body, candidate);
      if (count <= best) continue;
      best = count;
      bestDelimiter = candidate;
    }
    return bestDelimiter;
  }

  private static int countOccurrences(String text, char target) {
    int count = 0;
    for (int i = 0; i < text.length(); ++i) {
      if (text.charAt(i) != target) continue;
      ++count;
    }
    return count;
  }

  private static List<String[]> parse(String body, char delimiter) {
    ArrayList<String[]> rows = new ArrayList<String[]>();
    ArrayList<String> cells = new ArrayList<String>();
    StringBuilder cell = new StringBuilder();
    boolean inQuotes = false;
    int length = body.length();
    for (int i = 0; i < length; ++i) {
      char c = body.charAt(i);
      if (inQuotes) {
        if (c == '\"') {
          if (i + 1 < length && body.charAt(i + 1) == '\"') {
            cell.append('\"');
            ++i;
            continue;
          }
          inQuotes = false;
          continue;
        }
        cell.append(c);
        continue;
      }
      if (c == '\"' && cell.length() == 0) {
        inQuotes = true;
        continue;
      }
      if (c == delimiter) {
        cells.add(cell.toString());
        cell.setLength(0);
        continue;
      }
      if (c == '\n') {
        cells.add(cell.toString());
        rows.add(cells.toArray(new String[0]));
        cells.clear();
        cell.setLength(0);
        continue;
      }
      if (c == '\r') continue;
      cell.append(c);
    }
    if (cell.length() > 0 || !cells.isEmpty()) {
      cells.add(cell.toString());
      rows.add(cells.toArray(new String[0]));
    }
    return rows;
  }

  private static void pad(StringBuilder out, int currentLength, int targetWidth) {
    for (int i = Math.min(currentLength, targetWidth); i < targetWidth; ++i) {
      out.append(' ');
    }
  }
}


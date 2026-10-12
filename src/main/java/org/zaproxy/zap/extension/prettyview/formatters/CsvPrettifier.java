package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.ArrayList;
import java.util.List;

public class CsvPrettifier
implements PrettyPrettifier {
  private static final int MAX_CELL_WIDTH = 60;
  private static final int MAX_ROWS = 5000;
  /**
   * Widest row that is still kept cell by cell. A single row with millions of delimiters must not
   * grow the cell list without bound; overflow cells are folded into the last cell, so only the
   * column alignment of a pathological row degrades.
   */
  private static final int MAX_COLUMNS = 1024;
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
    Parsed parsed = CsvPrettifier.parse(body, delimiter);
    List<String[]> rows = parsed.rows;
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
    for (int r = 0; r < rows.size(); ++r) {
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
    if (parsed.totalRows > rows.size()) {
      stringBuilder.append("... ").append(parsed.totalRows - rows.size()).append(" further row(s) omitted\n");
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

  /** The first {@link #MAX_ROWS} rows plus the total row count, so the tail is counted, not kept. */
  private static final class Parsed {
    final List<String[]> rows;
    final int totalRows;

    Parsed(List<String[]> rows, int totalRows) {
      this.rows = rows;
      this.totalRows = totalRows;
    }
  }

  private static Parsed parse(String body, char delimiter) {
    ArrayList<String[]> rows = new ArrayList<String[]>();
    ArrayList<String> cells = new ArrayList<String>();
    StringBuilder cell = new StringBuilder();
    boolean inQuotes = false;
    int totalRows = 0;
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
        CsvPrettifier.addCell(cells, cell);
        cell.setLength(0);
        continue;
      }
      if (c == '\n') {
        CsvPrettifier.addCell(cells, cell);
        ++totalRows;
        if (rows.size() < MAX_ROWS) {
          rows.add(cells.toArray(new String[0]));
        }
        cells.clear();
        cell.setLength(0);
        continue;
      }
      if (c == '\r') continue;
      cell.append(c);
    }
    if (cell.length() > 0 || !cells.isEmpty()) {
      CsvPrettifier.addCell(cells, cell);
      ++totalRows;
      if (rows.size() < MAX_ROWS) {
        rows.add(cells.toArray(new String[0]));
      }
    }
    return new Parsed(rows, totalRows);
  }

  /** Adds a cell, folding overflow into the last cell once the row is at {@link #MAX_COLUMNS}. */
  private static void addCell(ArrayList<String> cells, StringBuilder cell) {
    if (cells.size() < MAX_COLUMNS) {
      cells.add(cell.toString());
      return;
    }
    int last = cells.size() - 1;
    cells.set(last, cells.get(last) + cell.toString());
  }

  private static void pad(StringBuilder out, int currentLength, int targetWidth) {
    for (int i = Math.min(currentLength, targetWidth); i < targetWidth; ++i) {
      out.append(' ');
    }
  }
}


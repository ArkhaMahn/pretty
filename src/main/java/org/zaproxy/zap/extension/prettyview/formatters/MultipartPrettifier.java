package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.zaproxy.zap.extension.prettyview.detect.ContentTypeSniffer;
import org.zaproxy.zap.extension.prettyview.detect.PayloadFormat;

public class MultipartPrettifier
implements PrettyPrettifier {
  private static final int MAX_PARTS = 500;
  private final UniversalPrettifierManager manager;

  public MultipartPrettifier(UniversalPrettifierManager manager) {
    this.manager = manager;
  }

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.MULTIPART;
  }

  @Override
  public String prettify(String body) throws PrettificationException {
    if (body == null || body.isEmpty()) {
      return "";
    }
    String boundary = MultipartPrettifier.extractBoundary(body);
    if (boundary == null) {
      throw new PrettificationException("No multipart boundary found");
    }
    String delimiter = "--" + boundary;
    String normalised = body.replace("\r\n", "\n");
    ArrayList<String> chunks = new ArrayList<String>();
    for (String chunk : MultipartPrettifier.split(normalised, delimiter)) {
      if (chunk.isEmpty() || "--".equals(chunk.trim())) continue;
      chunks.add(chunk);
    }
    int shown = Math.min(chunks.size(), 500);
    StringBuilder out = new StringBuilder(Math.min(normalised.length() * 2, 65536));
    out.append("multipart/form-data  (").append(shown).append(" part(s))\n");
    out.append("boundary = ").append(boundary).append('\n');
    int index = 0;
    for (String chunk : chunks) {
      if (index >= 500) {
        out.append("... ").append(chunks.size() - 500).append(" further part(s) omitted\n");
        break;
      }
      this.appendPart(out, chunk, ++index);
    }
    return out.toString();
  }

  private static String extractBoundary(String body) {
    String candidate;
    int firstLine = body.indexOf(10);
    String head = firstLine < 0 ? body : body.substring(0, firstLine);
    int marker = head.indexOf("--");
    if (marker >= 0 && !(candidate = head.substring(marker + 2).trim()).isEmpty() && candidate.length() < 200) {
      return candidate;
    }
    return null;
  }

  private static List<String> split(String body, String delimiter) {
    ArrayList<String> parts = new ArrayList<String>();
    int index = 0;
    while (index < body.length()) {
      int next = body.indexOf(delimiter, index);
      if (next < 0) {
        parts.add(body.substring(index));
        break;
      }
      parts.add(body.substring(index, next));
      index = next + delimiter.length();
    }
    return parts;
  }

  private void appendPart(StringBuilder out, String chunk, int index) {
    String cleaned = chunk.startsWith("\n") ? chunk.substring(1) : chunk;
    int separator = cleaned.indexOf("\n\n");
    String headers = separator < 0 ? cleaned : cleaned.substring(0, separator);
    String body = separator < 0 ? "" : cleaned.substring(separator + 2);
    out.append("\n").append("=".repeat(Math.min(index, 20))).append(" Part ").append(index).append(' ').append("=".repeat(20)).append('\n');
    String partContentType = null;
    for (String line : headers.split("\n")) {
      out.append(line.trim()).append('\n');
      String lower = line.toLowerCase(Locale.ROOT);
      if (!lower.startsWith("content-type:")) continue;
      partContentType = line.substring(line.indexOf(58) + 1).trim();
    }
    out.append('\n');
    out.append(this.formatBody(body, partContentType));
  }

  private String formatBody(String body, String partContentType) {
    String trimmed = MultipartPrettifier.stripTrailingNewline(body);
    if (trimmed.isEmpty() || this.manager == null) {
      return trimmed.isEmpty() ? "" : trimmed + "\n";
    }
    PayloadFormat format = ContentTypeSniffer.detect(partContentType, trimmed);
    return MultipartPrettifier.indent(this.manager.prettifyBody(trimmed, partContentType, format));
  }

  private static String stripTrailingNewline(String value) {
    String result = value;
    while (result.endsWith("\n")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private static String indent(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(text.length() + 32);
    for (String line : text.split("\n", -1)) {
      if (!line.isEmpty()) {
        out.append(PrettyPrettifier.INDENT).append(line);
      }
      out.append('\n');
    }
    return out.toString();
  }
}


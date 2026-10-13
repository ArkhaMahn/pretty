package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.Locale;
import org.zaproxy.zap.extension.prettyview.detect.ContentTypeSniffer;
import org.zaproxy.zap.extension.prettyview.detect.PayloadFormat;

/**
 * Multipart bodies with nothing invented: delimiter lines, part headers and framing pass through
 * byte for byte, and only structured part bodies get a whitespace-only reformat (anything else,
 * including plain text, goes back verbatim). In particular there are no part banners, no boundary
 * echoes, no header trimming and no omission footers - every part is shown.
 */
public class MultipartPrettifier
implements PrettyPrettifier {
  /**
   * Deepest nesting of {@code multipart/*} inside {@code multipart/*} that is still prettified.
   * A nested part re-enters this same prettifier through the manager, so crafted nesting would
   * otherwise recurse until the stack gives out; deeper parts are rendered verbatim instead.
   */
  private static final int MAX_NESTING_DEPTH = 5;
  private final UniversalPrettifierManager manager;
  private int nestingDepth;

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
    // Split keeping line endings out of the way but bytes intact: lines are rejoined with the
    // same '\n' they were split on, and any '\r' stays at its line end for the display to render.
    String[] lines = body.split("\n", -1);
    StringBuilder out = new StringBuilder(body.length() + 64);
    boolean inHeaders = false;
    boolean inBody = false;
    boolean seenDelimiter = false;
    boolean closed = false;
    StringBuilder partBody = new StringBuilder();
    String partContentType = null;
    for (String line : lines) {
      if (!closed && MultipartPrettifier.isDelimiter(line, delimiter)) {
        if (inBody) {
          MultipartPrettifier.emitPartBody(out, partBody.toString(), partContentType, this);
          partBody.setLength(0);
          partContentType = null;
          inBody = false;
        }
        if (MultipartPrettifier.isClosingDelimiter(line, delimiter)) {
          closed = true;
        }
        inHeaders = true;
        seenDelimiter = true;
        MultipartPrettifier.emitLine(out, line);
        continue;
      }
      if (closed) {
        // Epilogue after the closing delimiter passes through untouched.
        MultipartPrettifier.emitLine(out, line);
        continue;
      }
      if (!seenDelimiter) {
        // Preamble: anything before the first delimiter passes through untouched.
        MultipartPrettifier.emitLine(out, line);
        continue;
      }
      if (inHeaders) {
        MultipartPrettifier.emitLine(out, line);
        if (line.trim().isEmpty()) {
          inHeaders = false;
          inBody = true;
        } else {
          String lower = line.toLowerCase(Locale.ROOT);
          if (lower.startsWith("content-type:")) {
            int colon = line.indexOf(':');
            partContentType = colon < 0 ? "" : line.substring(colon + 1).trim();
          }
        }
        continue;
      }
      if (inBody) {
        if (partBody.length() > 0) {
          partBody.append('\n');
        }
        partBody.append(line);
        continue;
      }
      // Epilogue after the closing delimiter passes through untouched.
      MultipartPrettifier.emitLine(out, line);
    }
    if (inBody) {
      MultipartPrettifier.emitPartBody(out, partBody.toString(), partContentType, this);
    }
    return out.toString();
  }

  private static boolean isDelimiter(String line, String delimiter) {
    String bare = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    return bare.equals(delimiter) || bare.equals(delimiter + "--");
  }

  private static boolean isClosingDelimiter(String line, String delimiter) {
    String bare = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    return bare.equals(delimiter + "--");
  }

  /**
   * Emits a part body: structured payloads get a whitespace-only reformat, everything else goes
   * back verbatim. Plain text in particular is never folded here - its bytes are the payload.
   */
  private static void emitPartBody(
      StringBuilder out, String partBody, String partContentType, MultipartPrettifier owner)
      throws PrettificationException {
    String text = partBody;
    if (!text.trim().isEmpty() && owner.manager != null && owner.nestingDepth < MAX_NESTING_DEPTH) {
      PayloadFormat format = ContentTypeSniffer.detect(partContentType, text.trim());
      if (format != null && format != PayloadFormat.PLAIN_TEXT) {
        ++owner.nestingDepth;
        try {
          text = owner.manager.prettifyBody(text, partContentType, format);
        } finally {
          --owner.nestingDepth;
        }
      }
    }
    if (out.length() > 0) {
      out.append('\n');
    }
    out.append(text);
    if (out.length() > PrettyPrettifier.MAX_OUTPUT_CHARS) {
      throw new PrettificationException("Formatted output exceeded its size cap; rendered verbatim");
    }
  }

  private static void emitLine(StringBuilder out, String line) {
    if (out.length() > 0) {
      out.append('\n');
    }
    out.append(line);
  }

  private static String extractBoundary(String body) {
    int firstLine = body.indexOf('\n');
    String head = firstLine < 0 ? body : body.substring(0, firstLine);
    if (head.endsWith("\r")) {
      head = head.substring(0, head.length() - 1);
    }
    int marker = head.indexOf("--");
    if (marker >= 0) {
      String candidate = head.substring(marker + 2).trim();
      if (!candidate.isEmpty() && candidate.length() < 200) {
        return candidate;
      }
    }
    return null;
  }
}

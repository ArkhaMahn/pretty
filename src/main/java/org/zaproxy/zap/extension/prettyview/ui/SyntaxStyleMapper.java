package org.zaproxy.zap.extension.prettyview.ui;

import java.util.Locale;
import org.zaproxy.zap.extension.prettyview.detect.PayloadFormat;

public final class SyntaxStyleMapper {
  private SyntaxStyleMapper() {
  }

  public static String syntaxStyleOf(PayloadFormat format) {
    if (format == null) {
      return "text/plain";
    }
    switch (format) {
      case JSON: {
        return "text/json";
      }
      case GRAPHQL: {
        return "text/json";
      }
      case HTML: {
        return "text/html";
      }
      case XML: {
        return "text/xml";
      }
      case CSS: {
        return "text/css";
      }
      case JAVASCRIPT: {
        return "text/javascript";
      }
      case FORM_URLENCODED: {
        return "text/properties";
      }
      case SQL: {
        return "text/sql";
      }
      case CSV: {
        return "text/csv";
      }
      case MARKDOWN: {
        return "text/markdown";
      }
      case MULTIPART: {
        // A multipart body is a run of MIME part headers followed by a blank line and the part body. There
        // is no MIME tokenizer, but each header is a "Name: value" line, which is what the properties
        // tokenizer already colours - so the part headers come out readable instead of as one flat block.
        return "text/properties";
      }
    }
    return "text/plain";
  }

  public static String labelOf(PayloadFormat format) {
    return format == null ? "plain text" : format.name().toLowerCase(Locale.ROOT);
  }
}


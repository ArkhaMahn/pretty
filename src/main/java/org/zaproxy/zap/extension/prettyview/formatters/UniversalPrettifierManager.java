package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.EnumMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zaproxy.zap.extension.prettyview.detect.ContentTypeSniffer;
import org.zaproxy.zap.extension.prettyview.detect.MessageSplitter;
import org.zaproxy.zap.extension.prettyview.detect.PayloadFormat;

public final class UniversalPrettifierManager {
  private static final Logger LOGGER = LogManager.getLogger(UniversalPrettifierManager.class);
  /**
   * Largest formatted output that is still shown. Pretty-printing multiplies size - 2.5 MB of
   * minified JSON unfolds to ~9 MB - and past this the document plus its token heap is not worth
   * holding; the raw body goes back instead, through the same fast path as an over-threshold input.
   */
  static final int MAX_FORMATTED_CHARS = 0xC00000;
  private final Map<PrettyPrettifier.SupportedFormat, PrettyPrettifier> prettifiers;
  private final PrettifyThreshold threshold;

  public UniversalPrettifierManager() {
    this(PrettifyThreshold.defaultThreshold());
  }

  public UniversalPrettifierManager(PrettifyThreshold threshold) {
    this.threshold = threshold;
    this.prettifiers = new EnumMap<PrettyPrettifier.SupportedFormat, PrettyPrettifier>(PrettyPrettifier.SupportedFormat.class);
    this.register(new JsonPrettifier());
    this.register(new GraphQlPrettifier());
    this.register(new HtmlPrettifier());
    this.register(new XmlPrettifier());
    this.register(new CssPrettifier());
    this.register(new JavaScriptPrettifier());
    this.register(new FormUrlPrettifier());
    this.register(new MultipartPrettifier(this));
    this.register(new SqlPrettifier());
    this.register(new CsvPrettifier());
    this.register(new PlainTextPrettifier());
  }

  private void register(PrettyPrettifier prettifier) {
    this.prettifiers.put(prettifier.getSupportedFormat(), prettifier);
  }

  public PrettyResult prettifyMessage(String message, String contentType) {    MessageSplitter.Parts parts = MessageSplitter.split(message);
    if (!parts.hasBody()) {
      return PrettyResult.formatted(parts.getHeaders(), PayloadFormat.PLAIN_TEXT);
    }
    PayloadFormat format = this.detectFormat(contentType, parts.getBody());
    String body = parts.getBody();
    if (!this.threshold.allowsPrettifying(body.length())) {
      LOGGER.debug("Body of {} chars exceeds the prettify threshold; rendering the fast raw view.", (Object)body.length());
      return PrettyResult.rawOverThreshold(parts.getHeaders() + "\n\n" + body, format, this.threshold.exceededNote(body.length()));
    }
    String formatted = this.prettifyBody(body, contentType, format);
    if (formatted.length() > MAX_FORMATTED_CHARS) {
      LOGGER.debug("Formatted output of {} chars exceeds the formatted cap; rendering the fast raw view.", (Object)formatted.length());
      return PrettyResult.rawOverThreshold(parts.getHeaders() + "\n\n" + body, format, this.threshold.formattedExceededNote(formatted.length()));
    }
    return PrettyResult.formatted(parts.getHeaders() + "\n\n" + formatted, format);
  }

  /**
   * Formats a bare body with no headers, for views hosted by a split (Header/Body) component.
   *
   * <p>{@link #prettifyMessage} expects a whole message: fed a bare body it finds no header/body
   * boundary, files the text as headers, and reports {@code PLAIN_TEXT}, which would pin every
   * split view read-only. This detects and formats the body directly so the result carries the
   * real format and the editability gate sees it.
   */
  public PrettyResult prettifyBodyMessage(String body, String contentType) {
    String text = body == null ? "" : body;
    PayloadFormat format = this.detectFormat(contentType, text);
    if (!this.threshold.allowsPrettifying(text.length())) {
      LOGGER.debug("Body of {} chars exceeds the prettify threshold; rendering the fast raw view.", (Object)text.length());
      return PrettyResult.rawOverThreshold(text, format, this.threshold.exceededNote(text.length()));
    }
    String formatted = this.prettifyBody(text, contentType, format);
    if (formatted.length() > MAX_FORMATTED_CHARS) {
      LOGGER.debug("Formatted output of {} chars exceeds the formatted cap; rendering the fast raw view.", (Object)formatted.length());
      return PrettyResult.rawOverThreshold(text, format, this.threshold.formattedExceededNote(formatted.length()));
    }
    return PrettyResult.formatted(formatted, format);
  }

  public String prettifyBody(String body, String contentType, PayloadFormat knownFormat) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    if (!this.threshold.allowsPrettifying(body.length())) {
      LOGGER.debug("Body of {} chars exceeds the prettify threshold; rendering verbatim.", (Object)body.length());
      return body;
    }
    PayloadFormat format = knownFormat != null ? knownFormat : this.detectFormat(contentType, body);
    PrettyPrettifier prettifier = this.prettifiers.get((Object)PrettyPrettifier.SupportedFormat.of(format));
    if (prettifier == null) {
      return body;
    }
    try {
      String formatted = prettifier.prettify(body);
      if (formatted == null || formatted.trim().isEmpty()) {
        return body;
      }
      return formatted;
    }
    catch (Throwable t) {
      LOGGER.debug("{} prettifier failed; rendering verbatim.", (Object)format, (Object)t);
      return body;
    }
  }

  public PayloadFormat detectFormat(String contentType, String body) {
    return ContentTypeSniffer.detect(contentType, body);
  }

  public PrettyPrettifier getPrettifier(PayloadFormat format) {
    return this.prettifiers.get((Object)PrettyPrettifier.SupportedFormat.of(format));
  }

  public PrettifyThreshold getThreshold() {
    return this.threshold;
  }
}


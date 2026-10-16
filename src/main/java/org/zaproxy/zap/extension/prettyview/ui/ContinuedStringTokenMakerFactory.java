package org.zaproxy.zap.extension.prettyview.ui;

import java.util.Set;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

/**
 * Wraps every token maker the delegate supplies so that a string split across two displayed lines keeps
 * its colouring on both halves. See {@link ContinuedStringTokenMaker}.
 *
 * <p>The stack also paints the HTTP header region Burp-style (see {@link HeaderRegionTokenMaker}) for
 * every language, and reads JSON property names as text (see {@link JsonKeyTokenMaker}). The header
 * boundary is captured per load: the view sets it before building the document, and each maker keeps
 * the value it was created with, so a load racing an older paint never colours by the wrong region.
 */
final class ContinuedStringTokenMakerFactory extends TokenMakerFactory {

  private final TokenMakerFactory delegate;
  private volatile int headerEnd;

  ContinuedStringTokenMakerFactory(TokenMakerFactory delegate) {
    this.delegate = delegate;
  }

  /** Where the header region ends in the display text about to be built, or zero for none. */
  void setHeaderEnd(int headerEnd) {
    this.headerEnd = Math.max(0, headerEnd);
  }

  @Override
  protected TokenMaker getTokenMakerImpl(String key) {
    TokenMaker maker;
    if ("text/sse".equals(key)) {
      // No stock tokenizer colours event streams; the delegate would answer null and the editor
      // would fall back to plain text.
      maker = new SseTokenMaker();
    } else if ("text/form".equals(key)) {
      // No stock tokenizer knows '&' separators; the properties tokenizer reads only the first
      // pair and swallows the rest into one value token.
      maker = new FormTokenMaker();
    } else if ("text/json".equals(key)) {
      maker = this.delegate.getTokenMaker(key);
      if (maker == null) {
        return null;
      }
      maker = new JsonKeyTokenMaker(new ContinuedStringTokenMaker(maker));
    } else if ("text/css".equals(key)) {
      // Burp's CSS token boundaries (class and id selectors, hex colours, at-rules) do not line
      // up with the stock maker's, so the language gets its own tokenizer rather than a retype.
      maker = new ContinuedStringTokenMaker(new CssTokenMaker());
    } else if ("text/csv".equals(key)) {
      maker = this.delegate.getTokenMaker(key);
      if (maker == null) {
        return null;
      }
      maker = new CsvUniformTokenMaker(new ContinuedStringTokenMaker(maker));
    } else if ("text/javascript".equals(key)) {
      maker = this.delegate.getTokenMaker(key);
      if (maker == null) {
        return null;
      }
      maker = new JsBackquoteTokenMaker(new ContinuedStringTokenMaker(maker));
    } else {
      maker = this.delegate.getTokenMaker(key);
      if (maker == null) {
        return null;
      }
      maker = new ContinuedStringTokenMaker(maker);
    }
    return new HeaderRegionTokenMaker(maker, this.headerEnd);
  }

  @Override
  public Set<String> keySet() {
    Set<String> keys = new java.util.HashSet<>(this.delegate.keySet());
    keys.add("text/sse");
    keys.add("text/form");
    return keys;
  }
}
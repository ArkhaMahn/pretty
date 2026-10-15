package org.zaproxy.zap.extension.prettyview.ui;

import java.util.Set;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

/**
 * Wraps every token maker the delegate supplies so that a string split across two displayed lines keeps
 * its colouring on both halves. See {@link ContinuedStringTokenMaker}.
 */
final class ContinuedStringTokenMakerFactory extends TokenMakerFactory {

  private final TokenMakerFactory delegate;

  ContinuedStringTokenMakerFactory(TokenMakerFactory delegate) {
    this.delegate = delegate;
  }

  @Override
  protected TokenMaker getTokenMakerImpl(String key) {
    if ("text/sse".equals(key)) {
      // No stock tokenizer colours event streams; the delegate would answer null and the editor
      // would fall back to plain text.
      return new SseTokenMaker();
    }
    TokenMaker maker = this.delegate.getTokenMaker(key);
    return maker == null ? null : new ContinuedStringTokenMaker(maker);
  }

  @Override
  public Set<String> keySet() {
    Set<String> keys = new java.util.HashSet<>(this.delegate.keySet());
    keys.add("text/sse");
    return keys;
  }
}
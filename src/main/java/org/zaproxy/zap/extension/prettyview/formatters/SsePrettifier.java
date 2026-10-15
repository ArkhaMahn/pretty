package org.zaproxy.zap.extension.prettyview.formatters;

/**
 * Lines verbatim. A server-sent-events stream is already one field per line ({@code event:},
 * {@code data:}, {@code id:}, {@code retry:}, {@code :comment}, blank line per dispatch), so there
 * is nothing to lay out; reflowing it would merge dispatches and corrupt replay. Highlighting is
 * left to the SSE tokenizer.
 */
public class SsePrettifier
implements PrettyPrettifier {
  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.SSE;
  }

  @Override
  public String prettify(String body) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    return body;
  }
}

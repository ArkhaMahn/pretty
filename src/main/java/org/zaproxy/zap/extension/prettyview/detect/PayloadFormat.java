package org.zaproxy.zap.extension.prettyview.detect;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum PayloadFormat {
  JSON("application/json", "text/json", "application/x-json", "application/problem+json", "text/x-json"),
  GRAPHQL("application/graphql", "application/graphql+json", "application/graphql-response+json"),
  HTML("text/html", "application/xhtml+xml", "application/xhtml"),
  XML("text/xml", "application/xml", "image/svg+xml", "application/rss+xml", "application/atom+xml", "application/xslt+xml", "application/xul+xml"),
  CSS("text/css"),
  JAVASCRIPT("application/javascript", "text/javascript", "application/x-javascript", "application/ecmascript", "text/ecmascript", "application/x-ecmascript", "text/x-javascript", "module"),
  FORM_URLENCODED("application/x-www-form-urlencoded"),
  MULTIPART("multipart/form-data", "multipart/mixed", "multipart/related"),
  SQL("application/sql", "text/x-sql", "application/x-sql"),
  CSV("text/csv", "application/csv", "text/tab-separated-values"),
  MARKDOWN("text/markdown", "text/x-markdown"),
  PLAIN_TEXT("text/plain");

  private final List<String> mediaTypes;

  private PayloadFormat(String ... mediaTypes) {
    this.mediaTypes = Collections.unmodifiableList(Arrays.asList(mediaTypes));
  }

  public List<String> getMediaTypes() {
    return this.mediaTypes;
  }

  public boolean matchesMediaType(String mediaType) {
    return mediaType != null && this.mediaTypes.contains(mediaType);
  }

  public boolean isParsed() {
    return this != PLAIN_TEXT;
  }
}


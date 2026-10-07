package org.zaproxy.zap.extension.prettyview.detect;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContentTypeSniffer {
  private static final int SNIFF_WINDOW = 8192;
  private static final Pattern JSON_ARRAY_OR_OBJECT_START = Pattern.compile("^\\s*[\\[{]");
  private static final Pattern JSONP_START = Pattern.compile("^[\\w$.]+\\s*\\(\\s*(\\{|\\[)", 32);
  private static final Pattern HTML_START = Pattern.compile("^\\s*(<!doctype\\s+html|<html|<head|<body|<script|<style|<meta|<div|<span|<!--)", 2);
  private static final Pattern XML_START = Pattern.compile("^\\s*(<\\?xml|<svg|<!doctype\\s+(?!html)|<[a-zA-Z_:][\\w.:-]*[\\s/>])");
  /**
   * An explicit operation keyword, which settles the format even if a field is named "new" or "for".
   * The name is optional so an anonymous "mutation { … }" is recognised too.
   */
  private static final Pattern GRAPHQL_OPERATION = Pattern.compile("^\\s*(query|mutation|subscription|fragment)\\b", 2);
  /** The shorthand form is just a brace containing fields, so it is only trusted once JavaScript is ruled out. */
  private static final Pattern GRAPHQL_SHORTHAND = Pattern.compile("\\A\\s*\\{\\s*\\w+\\s*(\\(|\\{)");
  private static final Pattern SQL_START = Pattern.compile("^\\s*(select|insert\\s+into|update|delete\\s+from|create\\s+table|drop\\s+table|alter\\s+table|with)\\b", 2);
  private static final Pattern CSS_BLOCK = Pattern.compile("[^{}]{1,200}\\{[^}]{0,2000}\\}");
  private static final Pattern FORM_PAIR = Pattern.compile("[^&=]+=[^&]*");
  private static final Pattern MARKDOWN_HINT = Pattern.compile("^\\s*(#{1,6}\\s+\\S|```|\\*\\s+\\S|\\d+\\.\\s+\\S)", 8);
  /** Comments and string literals, removed before the JavaScript signature is looked for. */
  private static final Pattern JS_COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", 8);
  private static final Pattern JS_STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`", 8);
  /**
   * A JavaScript keyword used as a word, which is the strongest cheap signal that a body is a script.
   * {@code in} and {@code of} are excluded because CSS values such as {@code ease-in-out} contain
   * them, and a preceding hyphen is excluded so vendor prefixes and hyphenated values cannot match.
   */
  private static final Pattern JS_KEYWORD = Pattern.compile(
      "(?:^|[^\\w$.-])(?:function|var|let|const|return|if|else|for|while|do|switch|case|default|break|continue|try|catch|finally|throw|new|typeof|instanceof|delete|void|class|extends|super|this|import|export|async|await|yield)(?:[^\\w$]|$)");
  /**
   * Media types whose payload is binary rather than text. These carry no formatter, so they are
   * shown verbatim; guessing from the bytes instead lets binary be read as form data, which URL
   * decodes it and silently corrupts it. {@code application/octet-stream} is deliberately absent:
   * it is a generic catch-all that servers also use for scripts, so the body still decides.
   */
  private static final Set<String> BINARY_MEDIA_TYPES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
      "application/protobuf", "application/x-protobuf",
      "application/x-google-protobuf", "application/google-protobuf", "application/proto",
      "application/grpc", "application/grpc+proto", "application/grpc-web",
      "application/grpc-web+proto", "application/x-www-form-urlencoded-gzip",
      "application/pdf", "application/zip", "application/gzip", "application/x-gzip",
      "application/x-tar", "application/x-bzip2", "application/wasm", "application/x-ndjson-seq")));

  private ContentTypeSniffer() {
  }

  public static PayloadFormat detect(String contentType, String body) {
    String trimmedBody;
    String string = trimmedBody = body == null ? "" : body;
    if (trimmedBody.trim().isEmpty()) {
      return PayloadFormat.PLAIN_TEXT;
    }
    if (ContentTypeSniffer.isBinaryMediaType(ContentTypeSniffer.extractMediaType(contentType))) {
      return PayloadFormat.PLAIN_TEXT;
    }
    if (ContentTypeSniffer.looksBinary(trimmedBody)) {
      return PayloadFormat.PLAIN_TEXT;
    }
    PayloadFormat fromHeader = ContentTypeSniffer.fromContentType(contentType);
    if (fromHeader != null) {
      return ContentTypeSniffer.refineWithBody(fromHeader, trimmedBody);
    }
    return ContentTypeSniffer.fromBody(trimmedBody);
  }

  private static boolean isBinaryMediaType(String mediaType) {
    if (mediaType.isEmpty()) {
      return false;
    }
    if (BINARY_MEDIA_TYPES.contains(mediaType)) {
      return true;
    }
    int slash = mediaType.indexOf('/');
    if (slash < 0) {
      return false;
    }
    String topLevel = mediaType.substring(0, slash);
    return topLevel.equals("image") || topLevel.equals("audio")
        || topLevel.equals("video") || topLevel.equals("font");
  }

  /**
   * Reports whether the leading window holds bytes that cannot occur in text, such as the NUL and
   * C0 control bytes used by protobuf length prefixes and other binary encodings. Only the window
   * is inspected, like every other signal here, because a binary payload announces itself in its
   * first few bytes. Tab, newline and carriage return are excluded, since text does contain them.
   */
  private static boolean looksBinary(String body) {
    int end = Math.min(body.length(), SNIFF_WINDOW);
    for (int i = 0; i < end; ++i) {
      char c = body.charAt(i);
      if (c == '\t' || c == '\n' || c == '\r') {
        continue;
      }
      if (c < 0x20 || c == 0x7F) {
        return true;
      }
    }
    return false;
  }

  public static PayloadFormat fromContentType(String contentType) {
    String mediaType = ContentTypeSniffer.extractMediaType(contentType);
    if (mediaType.isEmpty()) {
      return null;
    }
    for (PayloadFormat format : PayloadFormat.values()) {
      if (!format.matchesMediaType(mediaType)) continue;
      return format;
    }
    if (ContentTypeSniffer.isJavaScriptMediaType(mediaType)) {
      return PayloadFormat.JAVASCRIPT;
    }
    if (mediaType.endsWith("+json")) {
      return PayloadFormat.JSON;
    }
    if (mediaType.endsWith("+xml")) {
      return PayloadFormat.XML;
    }
    return null;
  }

  /**
   * Recognises JavaScript by its subtype rather than by an exact match, so vendor and custom types
   * still work: {@code text/x-javascript}, {@code application/javascript1.5},
   * {@code application/vnd.acme.javascript}, {@code application/ld+javascript},
   * {@code application/x-js} and {@code text/ecmascript}. A trailing {@code js} token is also
   * accepted, since nothing outside JavaScript uses one.
   */
  private static boolean isJavaScriptMediaType(String mediaType) {
    int slash = mediaType.indexOf('/');
    if (slash < 0) {
      return false;
    }
    String subtype = mediaType.substring(slash + 1);
    if (subtype.contains("javascript") || subtype.contains("ecmascript")) {
      return true;
    }
    return subtype.equals("js") || subtype.endsWith("-js") || subtype.endsWith(".js") || subtype.endsWith("+js");
  }

  public static String extractMediaType(String contentType) {
    if (contentType == null) {
      return "";
    }
    int semicolon = contentType.indexOf(59);
    String mediaType = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
    return mediaType.trim().toLowerCase(Locale.ROOT);
  }

  public static PayloadFormat fromBody(String body) {
    String window = body.substring(0, Math.min(body.length(), 8192));
    String probe = window.trim();
    if (HTML_START.matcher(probe).find()) {
      return PayloadFormat.HTML;
    }
    if (GRAPHQL_OPERATION.matcher(probe).find()) {
      return PayloadFormat.GRAPHQL;
    }
    // Before the shorthand form, which is only a brace containing fields, so "{b()}" - the body of
    // a JavaScript block - would otherwise read as a query selection.
    if (ContentTypeSniffer.looksLikeJavaScript(probe)) {
      return PayloadFormat.JAVASCRIPT;
    }
    if (GRAPHQL_SHORTHAND.matcher(probe).find()) {
      return PayloadFormat.GRAPHQL;
    }
    if (JSON_ARRAY_OR_OBJECT_START.matcher(probe).find() && ContentTypeSniffer.looksLikeJson(window)) {
      return PayloadFormat.JSON;
    }
    if (XML_START.matcher(probe).find()) {
      return PayloadFormat.XML;
    }
    if (JSONP_START.matcher(probe).find()) {
      return PayloadFormat.JSON;
    }
    if (SQL_START.matcher(probe).find()) {
      return PayloadFormat.SQL;
    }
    if (ContentTypeSniffer.isFormUrlEncoded(probe)) {
      return PayloadFormat.FORM_URLENCODED;
    }
    if (CSS_BLOCK.matcher(probe).find()) {
      return PayloadFormat.CSS;
    }
    if (MARKDOWN_HINT.matcher(probe).find()) {
      return PayloadFormat.MARKDOWN;
    }
    return PayloadFormat.PLAIN_TEXT;
  }

  /**
   * Detects a script by its keywords once comments and string literals are removed, so CSS such as
   * {@code a::before{content:"new"}} is not read as JavaScript just because a word matches. GraphQL
   * has no keywords, and neither has a CSS declaration block, so neither is captured.
   */
  private static boolean looksLikeJavaScript(String probe) {
    String bare = JS_STRING.matcher(probe).replaceAll("\"\"");
    bare = JS_COMMENT.matcher(bare).replaceAll(" ");
    if (JS_KEYWORD.matcher(bare).find()) {
      return true;
    }
    return bare.contains("=>");
  }

  private static PayloadFormat refineWithBody(PayloadFormat declared, String body) {
    PayloadFormat sniffed;
    if (declared == PayloadFormat.PLAIN_TEXT && (sniffed = ContentTypeSniffer.fromBody(body)) != PayloadFormat.PLAIN_TEXT) {
      return sniffed;
    }
    if (declared == PayloadFormat.MULTIPART && !body.contains("--")) {
      return PayloadFormat.PLAIN_TEXT;
    }
    return declared;
  }

  private static boolean looksLikeJson(String window) {
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = 0; i < window.length(); ++i) {
      char c = window.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
          continue;
        }
        if (c == '\\') {
          escaped = true;
          continue;
        }
        if (c != '\"') continue;
        inString = false;
        continue;
      }
      if (c == '\"') {
        inString = true;
        continue;
      }
      if (c == '{' || c == '[') {
        ++depth;
        continue;
      }
      if (c != '}' && c != ']' || --depth != 0) continue;
      return true;
    }
    return false;
  }

  private static boolean isFormUrlEncoded(String probe) {
    if (probe.indexOf(61) < 0 || probe.indexOf(32) >= 0) {
      return false;
    }
    String[] pairs = probe.split("&");
    if (pairs.length == 0 || pairs.length > 200) {
      return false;
    }
    int matched = 0;
    for (String pair : pairs) {
      Matcher matcher = FORM_PAIR.matcher(pair);
      if (!matcher.matches()) continue;
      ++matched;
    }
    return matched == pairs.length && matched > 0;
  }
}


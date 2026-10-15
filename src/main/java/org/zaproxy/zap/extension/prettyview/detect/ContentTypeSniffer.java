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
  /** A JavaScript keyword used as a word, which is the strongest cheap signal that a body is a script.
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
    if (ContentTypeSniffer.isJsonStart(window)) {
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
    // Before the form check: a single-line "data:a=1" carries '=' and no spaces, which the form
    // rule would otherwise read as a pair, and a "data: {...}" line carries braces the CSS rule
    // would read as a declaration block. The field-colon shape answers first instead, and a
    // genuine form never qualifies: pairs join with '=' rather than the ':' SSE fields require.
    if (ContentTypeSniffer.looksLikeSse(window)) {
      return PayloadFormat.SSE;
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
   * Whether the window reads as a server-sent-events stream: lines of {@code event:}, {@code
   * data:}, {@code id:} or {@code retry:} fields, or {@code :}-led comments. Two field lines are
   * required, so a lone {@code data:} line - a data URL, say - stays plain text. A linear scan on
   * purpose; no regex runs over the payload here.
   */
  private static boolean looksLikeSse(String window) {
    int fields = 0;
    int lineStart = 0;
    int n = window.length();
    for (int i = 0; i <= n && fields < 2; ++i) {
      if (i != n && window.charAt(i) != '\n') {
        continue;
      }
      int end = i;
      if (end > lineStart && window.charAt(end - 1) == '\r') {
        --end;
      }
      if (ContentTypeSniffer.isSseFieldLine(window, lineStart, end)) {
        ++fields;
      } else if (end != lineStart && !ContentTypeSniffer.isSseCommentLine(window, lineStart, end)) {
        return false;
      }
      lineStart = i + 1;
    }
    return fields >= 2;
  }

  private static boolean isSseCommentLine(String window, int from, int to) {
    return from < to && window.charAt(from) == ':';
  }

  private static boolean isSseFieldLine(String window, int from, int to) {
    int colon = -1;
    for (int i = from; i < to; ++i) {
      char c = window.charAt(i);
      if (c == ':') {
        colon = i;
        break;
      }
      if (c == ' ' || c == '\t') {
        return false;
      }
    }
    if (colon < 0) {
      return false;
    }
    int length = colon - from;
    return (length == 5 && window.regionMatches(from, "event", 0, 5))
        || (length == 4 && window.regionMatches(from, "data", 0, 4))
        || (length == 2 && window.regionMatches(from, "id", 0, 2))
        || (length == 5 && window.regionMatches(from, "retry", 0, 5));
  }

  /**
   * Detects a script by its keywords once comments and string literals are removed, so CSS such as
   * {@code a::before{content:"new"}} is not read as JavaScript just because a word matches. GraphQL
   * has no keywords, and neither has a CSS declaration block, so neither is captured.
   */
  private static boolean looksLikeJavaScript(String probe) {
    String bare = ContentTypeSniffer.stripStringsAndComments(probe);
    if (JS_KEYWORD.matcher(bare).find()) {
      return true;
    }
    return bare.contains("=>");
  }

  /**
   * Masks string literals and comments with a single space each, so keyword detection cannot be
   * spoofed by CSS content such as {@code {content:"new"}}.
   *
   * <p>This is a linear scanner on purpose. The one-liner regex with a {@code *}-quantified group
   * over a quoted literal recurses once per character in Java's engine and overflows the stack on a
   * long quoted run (a base64 value in JSON, for instance), which previously crashed the sniff; no
   * body content may do that, so nothing here runs a regex over the payload.
   */
  private static String stripStringsAndComments(String text) {
    int n = text.length();
    StringBuilder bare = new StringBuilder(n);
    int i = 0;
    while (i < n) {
      char c = text.charAt(i);
      if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
        int end = text.indexOf("*/", i + 2);
        i = end < 0 ? n : end + 2;
        bare.append(' ');
        continue;
      }
      if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
        int end = text.indexOf('\n', i + 2);
        i = end < 0 ? n : end;
        bare.append(' ');
        continue;
      }
      if (c == '"' || c == '\'' || c == '`') {
        int j = i + 1;
        while (j < n) {
          char d = text.charAt(j);
          if (d == '\\') {
            j += 2;
            continue;
          }
          if (d == c) {
            ++j;
            break;
          }
          ++j;
        }
        i = j;
        bare.append(' ');
        continue;
      }
      bare.append(c);
      ++i;
    }
    return bare.toString();
  }

  private static PayloadFormat refineWithBody(PayloadFormat declared, String body) {
    PayloadFormat sniffed;
    if (declared == PayloadFormat.PLAIN_TEXT && (sniffed = ContentTypeSniffer.fromBody(body)) != PayloadFormat.PLAIN_TEXT) {
      return sniffed;
    }
    if (declared == PayloadFormat.FORM_URLENCODED && ContentTypeSniffer.opensAsJsonOpener(body)) {
      // A form content type that turns out to carry a JSON payload is mislabeled, not a form
      // (Google's telemetry POSTs a JSON array under application/x-www-form-urlencoded). Letting
      // the form formatter keep it would read the whole array as one value-less field and tag it
      // "(no value)". A genuine form never opens with '{' or '[': a parameter name beginning with
      // one would have to be percent-encoded, and so cannot appear as a raw opener. The opener
      // alone decides rather than requiring a closing brace inside the sniff window, which would
      // miss a JSON payload whose first value is one very long string. Name-only fields such as
      // "flag" still keep their form treatment, since they open with a plain word.
      return PayloadFormat.JSON;
    }
    if (declared == PayloadFormat.MULTIPART && !body.contains("--")) {
      // A body without a single boundary marker is not a multipart body, only a mislabeled one.
      // Re-sniff it instead of pinning it to plain text, so a JSON (or any structured) body under
      // a multipart header still gets the view it actually is. JSON gets the same opener rule as
      // the form case above, so a closing brace past the sniff window cannot drop it to plain text.
      if (ContentTypeSniffer.opensAsJsonOpener(body)) {
        return PayloadFormat.JSON;
      }
      return ContentTypeSniffer.fromBody(body);
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

  /**
   * Whether the leading window opens as a JSON array or object and the braces later close, which
   * separates a JSON payload from a name-only form field ("{@code flag}") that begins with a plain
   * word. Used by body sniffing, where a lone opener is not enough: a JavaScript array literal or a
   * GraphQL selection set also opens with {@code '['} or {@code '{'}.
   */
  private static boolean isJsonStart(String text) {
    if (!ContentTypeSniffer.opensAsJsonOpener(text)) {
      return false;
    }
    int end = Math.min(text.length(), SNIFF_WINDOW);
    return ContentTypeSniffer.looksLikeJson(text.substring(0, end));
  }

  /** Whether the leading window opens with the brace that only a JSON value can start with. */
  private static boolean opensAsJsonOpener(String text) {
    int end = Math.min(text.length(), SNIFF_WINDOW);
    return JSON_ARRAY_OR_OBJECT_START.matcher(text.substring(0, end).trim()).find();
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


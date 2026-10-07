package org.zaproxy.zap.extension.prettyview.formatters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonPrettifier
implements PrettyPrettifier {
  private static final Pattern JSONP = Pattern.compile("^\\s*([\\w$.\\[\\]'\"\\-]+)\\s*\\((.*)\\)\\s*;?\\s*$", 32);
  /**
   * Used only to serialise. The indent is not this object's to set: {@code toJson} copies its own
   * htmlSafe and serializeNulls settings onto whatever writer it is handed, but leaves the writer's
   * indent alone, which is how {@link #writePretty} supplies {@link PrettyPrettifier#INDENT}.
   */
  private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.JSON;
  }

  @Override
  public String prettify(String body) throws PrettificationException {
    String payload = JsonPrettifier.unwrapJsonp(body);
    if (payload == null) {
      payload = body;
    }
    if (payload.trim().isEmpty()) {
      throw new PrettificationException("Empty JSON payload");
    }
    try {
      JsonElement element = JsonParser.parseString((String)payload);
      if (element == null || element.isJsonNull()) {
        throw new PrettificationException("JSON payload is null");
      }
      return JsonPrettifier.writePretty(element);
    }
    catch (JsonParseException | IllegalStateException e) {
      throw new PrettificationException("Malformed JSON: " + JsonPrettifier.rootMessage(e), e);
    }
  }

  /**
   * Serialises at {@link PrettyPrettifier#INDENT} rather than Gson's own two spaces: {@code
   * setPrettyPrinting} hardcodes them and exposes no setting, so the indent goes on the writer, which
   * Gson copies its own htmlSafe and serializeNulls flags onto but leaves the indent alone.
   */
  private static String writePretty(JsonElement element) throws PrettificationException {
    StringWriter out = new StringWriter();
    try (JsonWriter writer = new JsonWriter(out)) {
      writer.setIndent(PrettyPrettifier.INDENT);
      JsonPrettifier.GSON.toJson(element, writer);
    }
    catch (IOException | JsonIOException e) {
      throw new PrettificationException("Cannot write JSON payload", e);
    }
    return out.toString();
  }

  private static String unwrapJsonp(String body) {
    Matcher matcher = JSONP.matcher(body);
    if (!matcher.matches()) {
      return null;
    }
    String callback = matcher.group(1);
    if (callback.isEmpty() || !callback.matches("^[\\w$.]+$")) {
      return null;
    }
    String inner = matcher.group(2).trim();
    if (!inner.startsWith("{") && !inner.startsWith("[")) {
      return null;
    }
    return inner;
  }

  private static String rootMessage(Throwable throwable) {
    String message = throwable.getMessage();
    return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
  }
}


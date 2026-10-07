package org.zaproxy.zap.extension.prettyview.formatters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonPrettifier
implements PrettyPrettifier {
  private static final Pattern JSONP = Pattern.compile("^\\s*([\\w$.\\[\\]'\"\\-]+)\\s*\\((.*)\\)\\s*;?\\s*$", 32);
  private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

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
      return PRETTY_GSON.toJson(element);
    }
    catch (JsonParseException | IllegalStateException e) {
      throw new PrettificationException("Malformed JSON: " + JsonPrettifier.rootMessage(e), e);
    }
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


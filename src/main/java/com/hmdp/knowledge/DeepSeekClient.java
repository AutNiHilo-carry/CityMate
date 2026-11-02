package com.hmdp.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded, non-streaming transport. Never logs credentials, prompts or upstream error bodies. */
@Component
public class DeepSeekClient {
  private final boolean enabled;
  private final String key;
  private final String model;
  private final URL endpoint;
  private final int timeoutMillis;
  private final ObjectMapper json = new ObjectMapper();

  @Autowired
  public DeepSeekClient(
      @Value("${citymate.ai.enabled:false}") boolean enabled,
      @Value("${citymate.ai.api-key:}") String key,
      @Value("${citymate.ai.model:deepseek-flash}") String model)
      throws Exception {
    this(enabled, key, model, new URL("https://api.deepseek.com/chat/completions"), 20000);
  }

  DeepSeekClient(boolean enabled, String key, String model, URL endpoint, int timeoutMillis) {
    this.enabled = enabled;
    this.key = key;
    this.model = model;
    this.endpoint = endpoint;
    this.timeoutMillis = timeoutMillis;
  }

  public boolean isAvailable() {
    return enabled
        && key != null
        && !key.trim().isEmpty()
        && model != null
        && model.matches("[A-Za-z0-9._-]{1,80}");
  }

  public String complete(String system, String input) {
    if (!isAvailable()) {
      throw new Failure("MODEL_NOT_CONFIGURED");
    }
    HttpURLConnection connection = null;
    long deadline = System.nanoTime() + timeoutMillis * 1000000L;
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("model", model);
      payload.put("messages", Arrays.asList(message("system", system), message("user", input)));
      payload.put("stream", false);
      payload.put("max_tokens", 800);
      payload.put("thinking", java.util.Collections.singletonMap("type", "disabled"));
      payload.put("response_format", java.util.Collections.singletonMap("type", "json_object"));
      byte[] body = json.writeValueAsBytes(payload);
      connection = (HttpURLConnection) endpoint.openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(Math.min(3000, timeoutMillis));
      connection.setReadTimeout(timeoutMillis);
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Authorization", "Bearer " + key);
      connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(body.length);
      try (OutputStream output = connection.getOutputStream()) {
        output.write(body);
      }
      remaining(connection, deadline);
      int status = connection.getResponseCode();
      if (status != 200) {
        throw new Failure(status == 429 ? "MODEL_RATE_LIMITED" : "MODEL_UNAVAILABLE");
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (InputStream inputStream = connection.getInputStream()) {
        byte[] buffer = new byte[2048];
        while (true) {
          remaining(connection, deadline);
          int count = inputStream.read(buffer);
          if (count < 0) {
            break;
          }
          if (bytes.size() + count > 65536) {
            throw new Failure("INVALID_MODEL_RESPONSE");
          }
          bytes.write(buffer, 0, count);
        }
      }
      JsonNode choice = json.readTree(bytes.toByteArray()).path("choices").path(0);
      JsonNode content = choice.path("message").path("content");
      if (!"stop".equals(choice.path("finish_reason").asText())
          || !content.isTextual()
          || content.asText().length() > 8000) {
        throw new Failure("INVALID_MODEL_RESPONSE");
      }
      return content.asText();
    } catch (Failure failure) {
      throw failure;
    } catch (SocketTimeoutException failure) {
      throw new Failure("MODEL_TIMEOUT");
    } catch (Exception failure) {
      throw new Failure("MODEL_UNAVAILABLE");
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private static Map<String, String> message(String role, String content) {
    Map<String, String> message = new LinkedHashMap<>();
    message.put("role", role);
    message.put("content", content);
    return message;
  }

  private static void remaining(HttpURLConnection connection, long deadline)
      throws SocketTimeoutException {
    long millis = (deadline - System.nanoTime()) / 1000000L;
    if (millis <= 0) {
      throw new SocketTimeoutException();
    }
    connection.setReadTimeout((int) Math.max(1, millis));
  }

  public static class Failure extends RuntimeException {
    public Failure(String code) {
      super(code);
    }
  }
}

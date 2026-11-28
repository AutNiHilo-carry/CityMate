package com.hmdp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.StreamUtils;

class DeepSeekClientTest {
  private HttpServer server;
  private URL url;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    url = new URL("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void respond(int status, String body) {
    server.createContext(
        "/chat/completions",
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  private String failureCode(int timeout) {
    DeepSeekClient client = new DeepSeekClient(true, "test-only", "deepseek-flash", url, timeout);
    return assertThrows(DeepSeekClient.Failure.class, () -> client.complete("system", "question"))
        .getMessage();
  }

  @Test
  void boundedCompletionSetsReviewTemperatureAndRejectsExpiredBudget() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    server.createContext(
        "/chat/completions",
        exchange -> {
          body.set(StreamUtils.copyToString(exchange.getRequestBody(), StandardCharsets.UTF_8));
          byte[] response =
              ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":" + "{\"content\":\"ok\"}}]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    DeepSeekClient client = new DeepSeekClient(true, "test-only", "deepseek-flash", url, 3000);
    assertEquals(
        "MODEL_TIMEOUT",
        assertThrows(DeepSeekClient.Failure.class, () -> client.completeBounded("s", "q", 0))
            .getMessage());
    assertEquals(null, body.get());
    assertEquals("ok", client.completeBounded("s", "q", 1000));
    JsonNode temperature = new ObjectMapper().readTree(body.get()).path("temperature");
    assertEquals(true, temperature.isNumber());
    assertEquals(0, temperature.asInt());
  }

  @Test
  void nativeToolTurnPreservesToolReplyIdsAndDisablesThinking() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    server.createContext(
        "/chat/completions",
        exchange -> {
          body.set(StreamUtils.copyToString(exchange.getRequestBody(), StandardCharsets.UTF_8));
          byte[] response =
              ("{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"tool_calls\":["
                      + "{\"id\":\"call_2\",\"type\":\"function\",\"function\":{"
                      + "\"name\":\"listAreas\",\"arguments\":\"{}\"}}]}}]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    java.util.Map<String, Object> reply = new java.util.LinkedHashMap<>();
    reply.put("role", "tool");
    reply.put("content", "{\"status\":\"OK\"}");
    reply.put("tool_call_id", "call_1");
    DeepSeekClient client = new DeepSeekClient(true, "test-only", "deepseek-flash", url, 3000);
    JsonNode result =
        client.toolTurn(
            java.util.Collections.singletonList(reply), java.util.Collections.emptyList(), 3000);
    assertEquals("call_2", result.path("message").path("tool_calls").get(0).path("id").asText());
    JsonNode sent = new ObjectMapper().readTree(body.get());
    assertEquals("call_1", sent.path("messages").get(0).path("tool_call_id").asText());
    assertEquals("auto", sent.path("tool_choice").asText());
    assertEquals("disabled", sent.path("thinking").path("type").asText());
    assertEquals("json_object", sent.path("response_format").path("type").asText());
  }

  @Test
  void disabledAndMissingKeyNeverMakeRequest() {
    assertFalse(new DeepSeekClient(false, "test-only", "deepseek-flash", url, 500).isAvailable());
    DeepSeekClient missing = new DeepSeekClient(true, "", "deepseek-flash", url, 500);
    assertEquals(
        "MODEL_NOT_CONFIGURED",
        assertThrows(DeepSeekClient.Failure.class, () -> missing.complete("s", "q")).getMessage());
  }

  @Test
  void sendsBoundedJsonAndReadsContentOnly() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    server.createContext(
        "/chat/completions",
        exchange -> {
          body.set(StreamUtils.copyToString(exchange.getRequestBody(), StandardCharsets.UTF_8));
          auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] response =
              ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{"
                      + "\"content\":\"ok\",\"reasoning_content\":\"private\"}}]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    DeepSeekClient client = new DeepSeekClient(true, "test-only", "deepseek-flash", url, 3000);
    assertEquals("ok", client.complete("system", "question"));
    assertEquals("Bearer test-only", auth.get());
    JsonNode sent = new ObjectMapper().readTree(body.get());
    assertEquals("json_object", sent.path("response_format").path("type").asText());
    assertEquals("disabled", sent.path("thinking").path("type").asText());
    assertEquals(800, sent.path("max_tokens").asInt());
  }

  @Test
  void rateLimitDoesNotExposeUpstreamBody() {
    respond(429, "sensitive upstream body");
    assertEquals("MODEL_RATE_LIMITED", failureCode(1000));
  }

  @Test
  void redirectsAreNeverFollowed() {
    respond(302, "redirect");
    assertEquals("MODEL_UNAVAILABLE", failureCode(1000));
  }

  @Test
  void truncatedCompletionIsRejected() {
    respond(
        200,
        "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}");
    assertEquals("INVALID_MODEL_RESPONSE", failureCode(1000));
  }

  @Test
  void responseSizeIsLimited() {
    respond(200, new String(new char[66000]).replace('\0', 'x'));
    assertEquals("INVALID_MODEL_RESPONSE", failureCode(1000));
  }

  @Test
  void slowServerTimesOut() {
    server.createContext(
        "/chat/completions",
        exchange -> {
          try {
            Thread.sleep(350);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    assertEquals("MODEL_TIMEOUT", failureCode(100));
  }
}

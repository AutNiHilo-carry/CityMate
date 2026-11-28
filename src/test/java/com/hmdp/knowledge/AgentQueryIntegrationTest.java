package com.hmdp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.KnowledgePublishRequest;
import com.hmdp.service.IKnowledgeService;
import com.hmdp.utils.RedisConstants;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "citymate.reliability.scheduling-enabled=false",
      "hmdp.cdc.enabled=false",
      "hmdp.mq.enabled=false",
      "hmdp.analytics.enabled=false"
    })
class AgentQueryIntegrationTest {
  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private IKnowledgeService knowledge;
  @Autowired private com.hmdp.utils.CacheClient cache;
  @MockBean private DeepSeekClient model;
  private final ObjectMapper json = new ObjectMapper();
  private String token;

  @BeforeEach
  void setup() {
    assertEquals("hmdp_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    token = UUID.randomUUID().toString();
    redis.opsForHash().put(RedisConstants.LOGIN_USER_KEY + token, "id", "1010");
    redis.expire(RedisConstants.LOGIN_USER_KEY + token, 1, TimeUnit.MINUTES);
    redis.delete(Arrays.asList("limit:agent-query-ip:127.0.0.1", "limit:agent-query-user:1010"));
    when(model.isAvailable()).thenReturn(true);
  }

  @AfterEach
  void clear() {
    redis.delete(RedisConstants.LOGIN_USER_KEY + token);
  }

  private ResponseEntity<String> post(boolean login) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (login) {
      headers.set("authorization", token);
    }
    return http.postForEntity(
        "/agent/query", new HttpEntity<>("{\"query\":\"有哪些商户分类\"}", headers), String.class);
  }

  @Test
  void anonymousCannotStartModelLoop() {
    assertEquals(401, post(false).getStatusCodeValue());
    verify(model, never()).toolTurn(anyList(), anyList(), anyInt());
  }

  @Test
  void anonymousCannotReadConfirmationPreview() {
    assertEquals(
        401,
        http.getForEntity("/assistant/claim-preview?shopId=4&voucherId=10", String.class)
            .getStatusCodeValue());
  }

  @Test
  void previewThenRepeatedOrdinarySubmissionCreatesOneUnpaidOrder() throws Exception {
    jdbc.update(
        "INSERT INTO tb_voucher(id,shop_id,title,rules,pay_value,actual_value,type,status) "
            + "VALUES(991081,1,'assistant confirmation test','demo only',500,1000,0,1)");
    cache.invalidate("cache:voucher-list:1");
    HttpHeaders headers = new HttpHeaders();
    headers.set("authorization", token);
    JsonNode preview =
        json.readTree(
                http.exchange(
                        "/assistant/claim-preview?shopId=1&voucherId=991081",
                        org.springframework.http.HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class)
                    .getBody())
            .path("data");
    assertEquals(500, preview.path("payCents").asInt());
    assertTrue(preview.path("availableAtQuery").asBoolean());
    String path = "/voucher-order/ordinary/991081";
    String first =
        json.readTree(http.postForEntity(path, new HttpEntity<>(headers), String.class).getBody())
            .path("data")
            .asText();
    String second =
        json.readTree(http.postForEntity(path, new HttpEntity<>(headers), String.class).getBody())
            .path("data")
            .asText();
    assertEquals(first, second);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tb_voucher_order " + "WHERE user_id=1010 AND voucher_id=991081",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT status FROM tb_voucher_order WHERE id=?", Integer.class, first));
    assertEquals(
        500L,
        jdbc.queryForObject(
            "SELECT pay_value_snapshot FROM tb_voucher_order " + "WHERE id=?", Long.class, first));
  }

  @Test
  void authenticatedHttpLoopExecutesRealCatalogToolWithoutCreatingOrders() throws Exception {
    int before = jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class);
    JsonNode call =
        json.readTree(
            "{\"finish_reason\":\"tool_calls\",\"message\":{\"tool_calls\":["
                + "{\"id\":\"one\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"listShopTypes\",\"arguments\":\"{}\"}}]}}");
    JsonNode end =
        json.readTree(
            "{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + json.writeValueAsString(
                    "{\"needsClarification\":false," + "\"answer\":\"已查询当前商户分类\",\"citations\":[]}")
                + "}}");
    when(model.toolTurn(anyList(), anyList(), anyInt())).thenReturn(call, end);
    ResponseEntity<String> response = post(true);
    assertEquals(200, response.getStatusCodeValue());
    JsonNode data = json.readTree(response.getBody()).path("data");
    assertEquals("ANSWERED", data.path("status").asText());
    assertEquals("OK", data.path("steps").get(0).path("status").asText());
    assertTrue(
        data.path("observations").get(0).path("result").path("data").path("items").size() > 0);
    assertEquals(
        before, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
  }

  @Test
  void ipLimitReturnsActualHttp429InsteadOfSuccessEnvelope() {
    redis.opsForValue().set("limit:agent-query-ip:127.0.0.1", "6", 1, TimeUnit.MINUTES);
    assertEquals(429, post(true).getStatusCodeValue());
    verify(model, never()).toolTurn(anyList(), anyList(), anyInt());
  }

  @Test
  void realKnowledgeToolSearchAndSourceRecheckProduceCitedAnswer() throws Exception {
    String sourceKey = "agent-live-" + UUID.randomUUID();
    KnowledgePublishRequest source = new KnowledgePublishRequest();
    source.setSourceKey(sourceKey);
    source.setTitle("进度规则");
    source.setKind("PLATFORM_FAQ");
    source.setExpectedVersion(0);
    source.setContent("极光受理凭证不表示付款，SUCCESS表示订单已落库。");
    assertTrue(knowledge.publish(source).getSuccess());
    JsonNode call =
        json.readTree(
            "{\"finish_reason\":\"tool_calls\",\"message\":{\"tool_calls\":["
                + "{\"id\":\"rules\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"searchKnowledge\","
                + "\"arguments\":\"{\\\"query\\\":\\\"极光受理凭证\\\"}\"}}]}}");
    JsonNode end =
        json.readTree(
            "{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + json.writeValueAsString(
                    "{\"needsClarification\":false,\"answer\":\"SUCCESS不表示付款\",\"citations\":[1]}")
                + "}}");
    when(model.toolTurn(anyList(), anyList(), anyInt())).thenReturn(call, end);
    JsonNode data = json.readTree(post(true).getBody()).path("data");
    assertEquals("ANSWERED", data.path("status").asText());
    assertEquals("OK", data.path("steps").get(0).path("status").asText());
    assertEquals(sourceKey, data.path("sources").get(0).path("sourceKey").asText());
    assertEquals(1, data.path("sources").get(0).path("version").asInt());
  }
}

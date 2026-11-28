package com.hmdp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.tool.ReadOnlyAgentTools;
import com.hmdp.dto.AgentQueryRequest;
import com.hmdp.dto.UserDTO;
import com.hmdp.reliability.RateLimiter;
import com.hmdp.service.impl.ReadOnlyAgentService;
import com.hmdp.utils.UserHolder;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadOnlyAgentServiceTest {
  private final ObjectMapper json = new ObjectMapper();
  private DeepSeekClient model;
  private ReadOnlyAgentTools tools;
  private RateLimiter limiter;
  private ReadOnlyAgentService agent;
  private AgentQueryRequest request;

  @BeforeEach
  void setup() {
    model = mock(DeepSeekClient.class);
    tools = mock(ReadOnlyAgentTools.class);
    limiter = mock(RateLimiter.class);
    when(model.isAvailable()).thenReturn(true);
    when(limiter.allow(anyString(), anyInt(), anyLong())).thenReturn(true);
    when(tools.definitions()).thenReturn(Collections.emptyList());
    when(tools.supports(anyString()))
        .thenAnswer(
            call -> Arrays.asList("searchShops", "searchKnowledge").contains(call.getArgument(0)));
    when(tools.execute(anyString(), anyMap(), any(), anyLong()))
        .thenReturn(Collections.singletonMap("status", "OK"));
    when(tools.sourcesCurrent(anyList(), anyLong())).thenReturn(true);
    agent = new ReadOnlyAgentService(model, tools, limiter);
    UserDTO user = new UserDTO();
    user.setId(7L);
    UserHolder.saveUser(user);
    request = new AgentQueryRequest();
    request.setQuery("查找火锅店");
  }

  @AfterEach
  void clear() {
    UserHolder.removeUser();
  }

  private JsonNode calls(String id, String name, String args) throws Exception {
    Map<String, Object> fn = new LinkedHashMap<>();
    fn.put("name", name);
    fn.put("arguments", args);
    Map<String, Object> call = new LinkedHashMap<>();
    call.put("id", id);
    call.put("type", "function");
    call.put("function", fn);
    Map<String, Object> msg = new LinkedHashMap<>();
    msg.put("tool_calls", Collections.singletonList(call));
    msg.put("reasoning_content", "private-thought");
    Map<String, Object> choice = new LinkedHashMap<>();
    choice.put("finish_reason", "tool_calls");
    choice.put("message", msg);
    return json.valueToTree(choice);
  }

  private JsonNode end(boolean clarify, String answer, String citations) throws Exception {
    return json.readTree(
        "{\"finish_reason\":\"stop\",\"message\":{\"content\":"
            + json.writeValueAsString(
                "{\"needsClarification\":"
                    + clarify
                    + ",\"answer\":"
                    + json.writeValueAsString(answer)
                    + ",\"citations\":"
                    + citations
                    + "}")
            + "}}");
  }

  private String status() {
    return (String) agent.query(request).getData().get("status");
  }

  @Test
  void toolResultsAreFedBackWithMatchingIdsAndNoReasoning() throws Exception {
    when(model.toolTurn(anyList(), anyList(), anyInt()))
        .thenReturn(calls("call_1", "searchShops", "{\"typeId\":1}"))
        .thenAnswer(
            call -> {
              List<Map<String, Object>> history = call.getArgument(0);
              assertEquals("assistant", history.get(2).get("role"));
              assertFalse(history.get(2).containsKey("reasoning_content"));
              assertEquals("tool", history.get(3).get("role"));
              assertEquals("call_1", history.get(3).get("tool_call_id"));
              return end(false, "找到商户，请查看查询时信息", "[]");
            });
    Map<String, Object> result = agent.query(request).getData();
    assertEquals("ANSWERED", result.get("status"));
    assertEquals(true, result.get("readOnly"));
    assertEquals(1, ((List<?>) result.get("steps")).size());
    assertFalse(json.writeValueAsString(result).contains("private-thought"));
  }

  @Test
  void unknownWriteToolRejectsWholeBatchBeforeExecutingAnything() throws Exception {
    JsonNode good = calls("one", "searchShops", "{}");
    ((com.fasterxml.jackson.databind.node.ArrayNode) good.path("message").path("tool_calls"))
        .add(calls("two", "claimVoucher", "{}").path("message").path("tool_calls").get(0));
    when(model.toolTurn(anyList(), anyList(), anyInt())).thenReturn(good);
    assertEquals("INVALID_MODEL_RESPONSE", status());
    verify(tools, never()).execute(anyString(), anyMap(), any(), anyLong());
  }

  @Test
  void duplicateCallIdsStopRepeatedExecution() throws Exception {
    when(model.toolTurn(anyList(), anyList(), anyInt()))
        .thenReturn(calls("same", "searchShops", "{}"));
    assertEquals("INVALID_MODEL_RESPONSE", status());
    verify(tools, times(1)).execute(anyString(), anyMap(), any(), anyLong());
  }

  @Test
  void malformedAndTrailingJsonArgumentsFailClosed() throws Exception {
    for (String args : Arrays.asList("[]", "{", "{} {}")) {
      when(model.toolTurn(anyList(), anyList(), anyInt()))
          .thenReturn(calls("one", "searchShops", args));
      assertEquals("INVALID_MODEL_RESPONSE", status());
    }
    verify(tools, never()).execute(anyString(), anyMap(), any(), anyLong());
  }

  @Test
  void roundBudgetStopsAnEndlessPlanner() throws Exception {
    java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
    when(model.toolTurn(anyList(), anyList(), anyInt()))
        .thenAnswer(c -> calls("id_" + n.incrementAndGet(), "searchShops", "{}"));
    assertEquals("STEP_LIMIT", status());
    verify(model, times(5)).toolTurn(anyList(), anyList(), anyInt());
  }

  @Test
  void batchCannotExceedEightToolCalls() throws Exception {
    JsonNode batch = calls("id0", "searchShops", "{}");
    for (int i = 1; i < 9; i++) {
      ((com.fasterxml.jackson.databind.node.ArrayNode) batch.path("message").path("tool_calls"))
          .add(calls("id" + i, "searchShops", "{}").path("message").path("tool_calls").get(0));
    }
    when(model.toolTurn(anyList(), anyList(), anyInt())).thenReturn(batch);
    assertEquals("STEP_LIMIT", status());
    verify(tools, never()).execute(anyString(), anyMap(), any(), anyLong());
  }

  @Test
  void missingLocationCanBeClarifiedWithoutInventedResults() throws Exception {
    when(model.toolTurn(anyList(), anyList(), anyInt())).thenReturn(end(true, "请补充商圈或位置", "[]"));
    assertEquals("NEEDS_CLARIFICATION", status());
  }

  @Test
  void answerWithoutObservationsIsNotClaimedAsReliable() throws Exception {
    when(model.toolTurn(anyList(), anyList(), anyInt())).thenReturn(end(false, "凭空回答", "[]"));
    assertEquals("NO_EVIDENCE", status());
  }

  @Test
  void fabricatedCitationAndLinksAreRejected() throws Exception {
    when(model.toolTurn(anyList(), anyList(), anyInt()))
        .thenReturn(calls("one", "searchShops", "{}"), end(false, "结果", "[9]"));
    assertEquals("INVALID_MODEL_RESPONSE", status());
    when(model.toolTurn(anyList(), anyList(), anyInt()))
        .thenReturn(end(true, "https://bad.test", "[]"));
    assertEquals("INVALID_MODEL_RESPONSE", status());
  }

  @Test
  void sourceIsRevalidatedAfterGenerationAndStaleAnswersAreDiscarded() throws Exception {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("sourceKey", "faq");
    source.put("version", 1);
    source.put("citation", 1);
    source.put("text", "规则说明");
    when(model.completeBounded(anyString(), anyString(), anyInt()))
        .thenReturn(
            "{\"supported\":true,\"checks\":[{\"sentenceId\":1,"
                + "\"sourceType\":\"KNOWLEDGE\",\"sourceId\":1,\"quote\":\"规则说明\"}]}");
    Map<String, Object> found = new LinkedHashMap<>();
    found.put("status", "OK");
    found.put("data", Collections.singletonMap("evidence", Collections.singletonList(source)));
    when(tools.execute(eq("searchKnowledge"), anyMap(), any(), anyLong())).thenReturn(found);
    when(model.toolTurn(anyList(), anyList(), anyInt()))
        .thenReturn(
            calls("one", "searchKnowledge", "{\"query\":\"规则\"}"), end(false, "根据规则解释", "[1]"));
    assertEquals("ANSWERED", status());
    when(tools.sourcesCurrent(anyList(), anyLong())).thenReturn(false);
    when(model.toolTurn(anyList(), anyList(), anyInt()))
        .thenReturn(
            calls("two", "searchKnowledge", "{\"query\":\"规则\"}"), end(false, "过时回答", "[1]"));
    Map<String, Object> data = agent.query(request).getData();
    assertEquals("STALE_EVIDENCE", data.get("status"));
    assertTrue(((List<?>) data.get("sources")).isEmpty());
    assertTrue(((List<?>) data.get("observations")).isEmpty());
    assertFalse(data.get("answer").toString().contains("过时回答"));
  }

  @Test
  void rejectedSupportReviewDiscardsKnowledgeAnswerAndItsSources() throws Exception {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("citation", 1);
    source.put("text", "付费券需要付款。");
    Map<String, Object> found = new LinkedHashMap<>();
    found.put("status", "OK");
    found.put("data", Collections.singletonMap("evidence", Collections.singletonList(source)));
    when(tools.execute(eq("searchKnowledge"), anyMap(), any(), anyLong())).thenReturn(found);
    when(model.toolTurn(anyList(), anyList(), anyInt()))
        .thenReturn(calls("one", "searchKnowledge", "{}"), end(false, "随时可退款。", "[1]"));
    when(model.completeBounded(anyString(), anyString(), anyInt()))
        .thenReturn("{\"supported\":false}");
    Map<String, Object> result = agent.query(request).getData();
    assertEquals("INSUFFICIENT_EVIDENCE", result.get("status"));
    assertEquals("ANSWER_SUPPORT_UNSUPPORTED", result.get("failureReason"));
    assertTrue(((List<?>) result.get("sources")).isEmpty());
    assertFalse(result.get("answer").toString().contains("随时可退款"));
    when(model.toolTurn(anyList(), anyList(), anyInt()))
        .thenReturn(calls("two", "searchKnowledge", "{}"), end(true, "随时可退款吗？", "[1]"));
    assertEquals("INSUFFICIENT_EVIDENCE", status());
  }

  @Test
  void timeoutDoesNotLeakDetailsAndReleasesConcurrencyPermit() throws Exception {
    when(model.toolTurn(anyList(), anyList(), anyInt()))
        .thenThrow(new DeepSeekClient.Failure("MODEL_TIMEOUT"));
    for (int i = 0; i < 3; i++) {
      assertEquals("MODEL_TIMEOUT", status());
    }
  }

  @Test
  void invalidVoucherCitationCanBeRepairedOnceWithinTheSameBudget() throws Exception {
    when(model.toolTurn(anyList(), anyList(), anyInt()))
        .thenReturn(calls("one", "searchShops", "{}"), end(false, "查询时的商户信息", "[10]"))
        .thenAnswer(
            call -> {
              List<Map<String, Object>> messages = call.getArgument(0);
              Map<String, Object> feedback = messages.get(messages.size() - 1);
              assertEquals("system", feedback.get("role"));
              assertTrue(feedback.get("content").toString().contains("[]"));
              return end(false, "查询时的商户信息", "[]");
            });
    Map<String, Object> data = agent.query(request).getData();
    assertEquals("ANSWERED", data.get("status"));
    assertEquals(true, data.get("outputRepairAttempted"));
    verify(model, times(3)).toolTurn(anyList(), anyList(), anyInt());
    verify(tools, times(1)).execute(anyString(), anyMap(), any(), anyLong());
  }

  @Test
  void invalidCitationIsNeverSilentlyDroppedAndRepairCannotLoop() throws Exception {
    when(model.toolTurn(anyList(), anyList(), anyInt()))
        .thenReturn(calls("one", "searchShops", "{}"), end(false, "错误引用", "[10]"));
    Map<String, Object> data = agent.query(request).getData();
    assertEquals("INVALID_MODEL_RESPONSE", data.get("status"));
    assertEquals("FINAL_CITATIONS", data.get("failureReason"));
    assertEquals(true, data.get("outputRepairAttempted"));
    assertTrue(((List<?>) data.get("observations")).isEmpty());
    verify(model, times(3)).toolTurn(anyList(), anyList(), anyInt());
  }

  @Test
  void authenticationConfigurationAndRateLimitPreventModelCalls() {
    UserHolder.removeUser();
    assertFalse(agent.query(request).getSuccess());
    UserDTO user = new UserDTO();
    user.setId(7L);
    UserHolder.saveUser(user);
    when(model.isAvailable()).thenReturn(false);
    assertEquals("MODEL_NOT_CONFIGURED", status());
    when(model.isAvailable()).thenReturn(true);
    when(limiter.allow(anyString(), anyInt(), anyLong())).thenReturn(false);
    assertEquals("BUSY", status());
    verify(model, never()).toolTurn(anyList(), anyList(), anyInt());
  }
}

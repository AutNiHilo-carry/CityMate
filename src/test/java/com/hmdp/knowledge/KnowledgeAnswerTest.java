package com.hmdp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hmdp.dto.KnowledgeSearchRequest;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.reliability.AccessGuard;
import com.hmdp.reliability.RateLimiter;
import com.hmdp.service.IKnowledgeService;
import com.hmdp.service.impl.KnowledgeAnswerServiceImpl;
import com.hmdp.utils.UserHolder;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class KnowledgeAnswerTest {
  private final IKnowledgeService knowledge = mock(IKnowledgeService.class);
  private final DeepSeekClient model = mock(DeepSeekClient.class);
  private final KnowledgeAnswerServiceImpl service =
      new KnowledgeAnswerServiceImpl(knowledge, model);
  private final KnowledgeSearchRequest request = new KnowledgeSearchRequest();

  @BeforeEach
  void prepare() {
    request.setQuery("领取进度");
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("citation", 1);
    source.put("sourceKey", "claim-progress");
    source.put("version", 2);
    source.put("text", "SUCCESS 表示落库，不表示付款。");
    source.put("sourceUrl", "/api/knowledge/sources/claim-progress?version=2");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("evidence", Collections.singletonList(source));
    when(knowledge.search(request)).thenReturn(Result.ok(data));
    when(knowledge.source("claim-progress", 2)).thenReturn(Result.ok(new LinkedHashMap<>()));
    when(model.isAvailable()).thenReturn(true);
    when(model.complete(anyString(), anyString()))
        .thenReturn("{\"answerable\":true,\"answer\":\"SUCCESS 表示落库，不代表付款。\",\"citations\":[1]}");
  }

  @AfterEach
  void clear() {
    UserHolder.removeUser();
  }

  private String status() {
    return (String) service.answer(request).getData().get("status");
  }

  @Test
  void returnsAnswerWithServerSourcesAndRechecksVersion() {
    Result<Map<String, Object>> result = service.answer(request);
    assertTrue(result.getSuccess());
    assertEquals("ANSWERED", result.getData().get("status"));
    assertTrue(result.getData().get("sources").toString().contains("version=2"));
    verify(knowledge).source("claim-progress", 2);
  }

  @Test
  void noEvidenceNeverCallsPaidApi() {
    when(knowledge.search(request))
        .thenReturn(Result.ok(Collections.singletonMap("evidence", Collections.emptyList())));
    assertEquals("NO_EVIDENCE", status());
    verify(model, never()).complete(anyString(), anyString());
  }

  @Test
  void invalidScopeDoesNotCallModel() {
    when(knowledge.search(request)).thenReturn(Result.fail("店铺不匹配"));
    assertFalse(service.answer(request).getSuccess());
    verify(model, never()).complete(anyString(), anyString());
  }

  @Test
  void absentCredentialsReturnEvidenceWithoutPretendingGeneration() {
    when(model.isAvailable()).thenReturn(false);
    assertEquals("MODEL_NOT_CONFIGURED", status());
    verify(model, never()).complete(anyString(), anyString());
  }

  @Test
  void refusesMalformedEmptyUnknownDuplicateAndForgedCitations() {
    for (String raw :
        Arrays.asList(
            "not json",
            "{}",
            "{\"answerable\":true,\"answer\":\"x\",\"citations\":[]}",
            "{\"answerable\":true,\"answer\":\"x\",\"citations\":[2]}",
            "{\"answerable\":true,\"answer\":\"x\",\"citations\":[1.0]}",
            "{\"answerable\":true,\"answer\":\"x\",\"citations\":[1,1]}",
            "{\"answerable\":true,\"answer\":\"https://evil.test\",\"citations\":[1]}")) {
      when(model.complete(anyString(), anyString())).thenReturn(raw);
      assertEquals("INVALID_MODEL_RESPONSE", status(), raw);
    }
  }

  @Test
  void modelCanAbstain() {
    when(model.complete(anyString(), anyString())).thenReturn("{\"answerable\":false}");
    assertEquals("INSUFFICIENT_EVIDENCE", status());
  }

  @Test
  void withdrawnOrChangedSourceDiscardsWholeGeneratedAnswer() {
    when(knowledge.source("claim-progress", 2)).thenReturn(Result.fail("已撤回"));
    assertEquals("STALE_EVIDENCE", status());
  }

  @Test
  void timeoutIsExplicitAndSlotIsReleased() {
    when(model.complete(anyString(), anyString()))
        .thenThrow(new DeepSeekClient.Failure("MODEL_TIMEOUT"));
    for (int i = 0; i < 4; i++) {
      assertEquals("MODEL_TIMEOUT", status());
    }
  }

  @Test
  void onlyTwoModelRequestsCanRunAtOnce() throws Exception {
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    when(model.complete(anyString(), anyString()))
        .thenAnswer(
            call -> {
              started.countDown();
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("worker timed out");
              }
              return "{\"answerable\":false}";
            });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = pool.submit(() -> service.answer(request));
      Future<?> second = pool.submit(() -> service.answer(request));
      assertTrue(started.await(3, TimeUnit.SECONDS));
      assertEquals("BUSY", status());
      release.countDown();
      first.get(3, TimeUnit.SECONDS);
      second.get(3, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void answerRequiresLoginAndCanBeRateLimited() throws Exception {
    RateLimiter limiter = mock(RateLimiter.class);
    AccessGuard guard = new AccessGuard(limiter);
    MockHttpServletRequest http = new MockHttpServletRequest("POST", "/knowledge/answer");
    MockHttpServletResponse anonymous = new MockHttpServletResponse();
    assertFalse(guard.preHandle(http, anonymous, new Object()));
    assertEquals(401, anonymous.getStatus());
    UserDTO user = new UserDTO();
    user.setId(7L);
    UserHolder.saveUser(user);
    when(limiter.allow("knowledge-answer-user:7", 6, 60000)).thenReturn(true);
    when(limiter.allow("knowledge-answer-ip:127.0.0.1", 12, 60000)).thenReturn(true);
    assertTrue(guard.preHandle(http, new MockHttpServletResponse(), new Object()));
    when(limiter.allow("knowledge-answer-user:7", 6, 60000)).thenReturn(false);
    MockHttpServletResponse limited = new MockHttpServletResponse();
    assertFalse(guard.preHandle(http, limited, new Object()));
    assertEquals(429, limited.getStatus());
  }
}

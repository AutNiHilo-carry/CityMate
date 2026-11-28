package com.hmdp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceSupportCheckerTest {
  private final DeepSeekClient model = mock(DeepSeekClient.class);
  private final EvidenceSupportChecker checker = new EvidenceSupportChecker(model);

  private List<Map<String, Object>> sources() {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("citation", 3);
    source.put("text", "未付款超时取消。付款不代表支持退款。");
    return Collections.singletonList(source);
  }

  private String row(int sentence, String type, int source, String quote) {
    return "{\"sentenceId\":"
        + sentence
        + ",\"sourceType\":\""
        + type
        + "\",\"sourceId\":"
        + source
        + ",\"quote\":\""
        + quote
        + "\"}";
  }

  private EvidenceSupportChecker.Verdict check(String raw, String answer) {
    when(model.completeBounded(anyString(), anyString(), anyInt())).thenReturn(raw);
    return checker.check("怎么付款退款", answer, sources(), Collections.emptyList(), 2000);
  }

  @Test
  void acceptsCompleteReviewWithExactQuotesAndOriginalCitationIds() {
    assertEquals(
        EvidenceSupportChecker.Verdict.SUPPORTED,
        check(
            "{\"supported\":true,\"checks\":["
                + row(1, "KNOWLEDGE", 3, "未付款超时取消。")
                + ","
                + row(2, "KNOWLEDGE", 3, "付款不代表支持退款。")
                + "]}",
            "没付款超时会取消。不能据此确定能退款。"));
  }

  @Test
  void checksCannotOmitRepeatOrInventSentenceAndSourceIds() {
    for (String rows :
        Arrays.asList(
            row(1, "KNOWLEDGE", 3, "未付款超时取消。"),
            row(1, "KNOWLEDGE", 3, "未付款超时取消。") + "," + row(1, "KNOWLEDGE", 3, "未付款超时取消。"),
            row(1, "KNOWLEDGE", 1, "未付款超时取消。") + "," + row(2, "KNOWLEDGE", 3, "付款不代表支持退款。"))) {
      assertEquals(
          EvidenceSupportChecker.Verdict.UNVERIFIED,
          check("{\"supported\":true,\"checks\":[" + rows + "]}", "第一句。第二句。"));
    }
  }

  @Test
  void forgedQuoteAndMalformedOrTrailingVerdictsFailClosed() {
    for (String raw :
        Arrays.asList(
            "{}",
            "null",
            "{\"supported\":\"true\"}",
            "{\"supported\":false,\"supported\":true,\"checks\":[]}",
            "{\"supported\":true,\"checks\":[" + row(1, "KNOWLEDGE", 3, "可以无条件退款") + "]}",
            "{\"supported\":true,\"checks\":[" + row(1, "KNOWLEDGE", 3, "未付款超时取消。") + "]} {}")) {
      assertEquals(EvidenceSupportChecker.Verdict.UNVERIFIED, check(raw, "可以无条件退款。"));
    }
    assertEquals(
        EvidenceSupportChecker.Verdict.UNSUPPORTED, check("{\"supported\":false}", "可以无条件退款。"));
  }

  @Test
  void expiredBudgetDoesNotStartAnotherPaidCall() {
    assertEquals(
        EvidenceSupportChecker.Verdict.UNVERIFIED,
        checker.check("问题", "答案。", sources(), Collections.emptyList(), 0));
    verify(model, never()).completeBounded(anyString(), anyString(), anyInt());
  }

  @Test
  void timedOutReviewIsUnverified() {
    when(model.completeBounded(anyString(), anyString(), anyInt()))
        .thenThrow(new DeepSeekClient.Failure("MODEL_TIMEOUT"));
    assertEquals(
        EvidenceSupportChecker.Verdict.UNVERIFIED,
        checker.check("问题", "答案。", sources(), Collections.emptyList(), 1000));
  }

  @Test
  void mixedAnswerCanUseToolResultsButCannotUseThemToForgeAKnowledgeQuote() {
    Map<String, Object> observation = Collections.singletonMap("payCents", 500);
    String rows = row(1, "KNOWLEDGE", 3, "未付款超时取消。") + "," + row(2, "TOOL", 1, "500");
    when(model.completeBounded(anyString(), anyString(), anyInt()))
        .thenReturn("{\"supported\":true,\"checks\":[" + rows + "]}");
    assertEquals(
        EvidenceSupportChecker.Verdict.SUPPORTED,
        checker.check(
            "规则和券价", "未付款超时取消。券价5元。", sources(), Collections.singletonList(observation), 1000));
    assertEquals(
        EvidenceSupportChecker.Verdict.UNVERIFIED,
        checker.check("规则和券价", "未付款超时取消。券价5元。", sources(), Collections.emptyList(), 1000));
  }
}

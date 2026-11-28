package com.hmdp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeQueryTermsTest {
  @Test
  void aliasesSupplementRatherThanReplaceOriginalTerms() {
    Map<String, Double> terms = KnowledgeQueryTerms.weights("这家几点打烊");
    assertEquals(1.0, terms.get("打烊"));
    assertEquals(0.5, terms.get("营业"));
    assertEquals(0.5, terms.get("时间"));
    assertEquals(1.0, KnowledgeQueryTerms.weights("营业时间几点打烊").get("时间"));
  }

  @Test
  void negationAndNumbersSurviveAndUnknownNumbersDoNotBecomeOrderIds() {
    Map<String, Double> terms = KnowledgeQueryTerms.weights("请问没有停车场吗 123");
    assertTrue(terms.containsKey("没有"));
    assertEquals(1.0, terms.get("123"));
    assertFalse(terms.containsKey("请问"));
    assertFalse(KnowledgeQueryTerms.weights("设备编号123").containsKey("订单"));
    assertTrue(KnowledgeQueryTerms.weights("抢到了一个编号").containsKey("订单"));
  }

  @Test
  void expansionCannotCreateDocumentsOrInventSemanticMatches() {
    assertTrue(KnowledgeRanker.rank("几点打烊", Collections.emptyList()).isEmpty());
    assertFalse(KnowledgeQueryTerms.weights("法国签证什么材料").containsKey("付款"));
  }
}

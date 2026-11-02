package com.hmdp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hmdp.dto.KnowledgePublishRequest;
import com.hmdp.dto.KnowledgeSearchRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IKnowledgeService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "citylens.reliability.scheduling-enabled=false", "hmdp.cdc.enabled=false",
      "hmdp.mq.enabled=false", "hmdp.analytics.enabled=false"
    })
class KnowledgeIntegrationTest {
  @Autowired private IKnowledgeService knowledge;
  @Autowired private JdbcTemplate jdbc;
  private String prefix;

  @BeforeEach
  void guard() {
    assertEquals("hmdp_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    prefix = "test-" + UUID.randomUUID();
  }

  private KnowledgePublishRequest document(
      String suffix, String kind, Long shopId, String content) {
    KnowledgePublishRequest request = new KnowledgePublishRequest();
    request.setSourceKey(prefix + suffix);
    request.setTitle("知识规则验证");
    request.setKind(kind);
    request.setShopId(shopId);
    request.setExpectedVersion(0);
    request.setContent(content);
    return request;
  }

  private Result<Map<String, Object>> search(String query, Long shopId, Long voucherId) {
    KnowledgeSearchRequest request = new KnowledgeSearchRequest();
    request.setQuery(query);
    request.setShopId(shopId);
    request.setVoucherId(voucherId);
    request.setLimit(5);
    return knowledge.search(request);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> evidence(Result<Map<String, Object>> result) {
    assertTrue(result.getSuccess(), result.getErrorMsg());
    return (List<Map<String, Object>>) result.getData().get("evidence");
  }

  @Test
  void scopesCannotMixDifferentShopsAndMissingScopeOnlyReturnsFaq() {
    KnowledgePublishRequest one = document("-one", "SHOP_SERVICE", 1L, "星光专享停车凭证免费");
    KnowledgePublishRequest two = document("-two", "SHOP_SERVICE", 2L, "星光专享停车凭证收费");
    assertTrue(knowledge.publish(one).getSuccess());
    assertTrue(knowledge.publish(two).getSuccess());
    List<Map<String, Object>> found = evidence(search("星光专享停车凭证", 1L, null));
    assertTrue(found.stream().anyMatch(row -> one.getSourceKey().equals(row.get("sourceKey"))));
    assertFalse(found.stream().anyMatch(row -> two.getSourceKey().equals(row.get("sourceKey"))));
    assertTrue(evidence(search("星光专享停车凭证", null, null)).isEmpty());
  }

  @Test
  void updatesWithdrawalsAndEffectiveWindowsRemoveOldEvidenceButKeepHistory() {
    KnowledgePublishRequest request = document("-version", "PLATFORM_FAQ", null, "极光进度由本人查询");
    assertTrue(knowledge.publish(request).getSuccess());
    request.setExpectedVersion(1);
    request.setContent("极光进度需等待核对");
    assertTrue(knowledge.publish(request).getSuccess());
    List<Map<String, Object>> found = evidence(search("极光进度", null, null));
    assertTrue(
        found.stream()
            .anyMatch(
                row ->
                    request.getSourceKey().equals(row.get("sourceKey"))
                        && Integer.valueOf(2).equals(row.get("version"))));
    assertFalse(knowledge.source(request.getSourceKey(), 1).getSuccess());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tb_knowledge_revision WHERE source_key=?",
            Integer.class,
            request.getSourceKey()));
    assertTrue(knowledge.withdraw(request.getSourceKey(), 2).getSuccess());
    request.setExpectedVersion(2);
    assertFalse(knowledge.publish(request).getSuccess(), "撤回后的旧发布请求不能重新激活来源");
    assertTrue(evidence(search("极光进度", null, null)).isEmpty());
    KnowledgePublishRequest expired = document("-expired", "PLATFORM_FAQ", null, "暮光规则已经过期");
    expired.setValidFrom(LocalDateTime.now().minusHours(2));
    expired.setValidUntil(LocalDateTime.now().minusHours(1));
    assertTrue(knowledge.publish(expired).getSuccess());
    KnowledgePublishRequest future = document("-future", "PLATFORM_FAQ", null, "暮光规则尚未生效");
    future.setValidFrom(LocalDateTime.now().plusDays(1));
    assertTrue(knowledge.publish(future).getSuccess());
    assertFalse(
        evidence(search("暮光规则", null, null)).stream()
            .anyMatch(
                row ->
                    expired.getSourceKey().equals(row.get("sourceKey"))
                        || future.getSourceKey().equals(row.get("sourceKey"))));
    assertFalse(knowledge.source(expired.getSourceKey(), 1).getSuccess());
    assertFalse(knowledge.source(future.getSourceKey(), 1).getSuccess());
  }

  @Test
  void voucherRuleChangesInvalidateBothRetrievalAndCitationSource() {
    jdbc.update(
        "INSERT INTO tb_voucher(id,shop_id,title,rules,pay_value,actual_value,type,status) "
            + "VALUES(990801,1,'rag test','周末晨星专享，节假日除外',0,100,0,1) "
            + "ON DUPLICATE KEY UPDATE rules=VALUES(rules),status=1");
    KnowledgePublishRequest request = document("-voucher", "VOUCHER_RULE", 1L, "周末晨星专享，节假日除外");
    request.setVoucherId(990801L);
    assertTrue(knowledge.publish(request).getSuccess());
    assertTrue(
        evidence(search("晨星专享", 1L, 990801L)).stream()
            .anyMatch(row -> request.getSourceKey().equals(row.get("sourceKey"))));
    jdbc.update("UPDATE tb_voucher SET rules='仅工作日暮星专享' WHERE id=990801");
    assertFalse(
        evidence(search("晨星专享", 1L, 990801L)).stream()
            .anyMatch(row -> request.getSourceKey().equals(row.get("sourceKey"))));
    assertFalse(knowledge.source(request.getSourceKey(), 1).getSuccess());
    assertFalse(search("晨星专享", 2L, 990801L).getSuccess());
    request.setExpectedVersion(1);
    request.setContent("仅工作日暮星专享");
    assertTrue(knowledge.publish(request).getSuccess());
    assertTrue(knowledge.source(request.getSourceKey(), 2).getSuccess());
  }

  @Test
  void concurrentPublicationHasOneWinner() throws Exception {
    KnowledgePublishRequest initial = document("-race", "PLATFORM_FAQ", null, "流星发布第一版");
    assertTrue(knowledge.publish(initial).getSuccess());
    initial.setExpectedVersion(1);
    initial.setContent("流星发布第二版");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> publish = () -> knowledge.publish(initial).getSuccess();
      Future<Boolean> first = executor.submit(publish);
      Future<Boolean> second = executor.submit(publish);
      assertEquals(
          1,
          (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT current_version FROM tb_knowledge_source WHERE source_key=?",
              Integer.class,
              initial.getSourceKey()));
    } finally {
      executor.shutdownNow();
    }
  }
}

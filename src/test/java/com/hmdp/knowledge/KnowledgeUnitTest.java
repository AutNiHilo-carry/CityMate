package com.hmdp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hmdp.dto.KnowledgePublishRequest;
import com.hmdp.dto.KnowledgeSearchRequest;
import com.hmdp.dto.Result;
import com.hmdp.entity.KnowledgeChunk;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.KnowledgeMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.impl.KnowledgeServiceImpl;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class KnowledgeUnitTest {
  private final KnowledgeMapper mapper = mock(KnowledgeMapper.class);
  private final IShopService shops = mock(IShopService.class);
  private final IVoucherService vouchers = mock(IVoucherService.class);
  private final TransactionTemplate transactions = mock(TransactionTemplate.class);
  private final KnowledgeServiceImpl service =
      new KnowledgeServiceImpl(mapper, transactions, shops, vouchers);

  private String repeat(String value, int count) {
    StringBuilder text = new StringBuilder();
    for (int index = 0; index < count; index++) {
      text.append(value);
    }
    return text.toString();
  }

  private KnowledgeChunk chunk(long id, String title, String text) {
    KnowledgeChunk chunk = new KnowledgeChunk();
    chunk.setChunkId(id);
    chunk.setSourceKey("faq-test-" + id);
    chunk.setVersion(1);
    chunk.setTitle(title);
    chunk.setContent(text);
    chunk.setKind("PLATFORM_FAQ");
    return chunk;
  }

  private KnowledgeSearchRequest query(String text) {
    KnowledgeSearchRequest request = new KnowledgeSearchRequest();
    request.setQuery(text);
    return request;
  }

  @Test
  void voucherRulesRemainWholeAndOversizedRulesAreRejected() {
    String rules = "周末可用，节假日除外。";
    assertEquals(Collections.singletonList(rules), KnowledgeText.chunks(rules, true));
    assertEquals(1, KnowledgeText.chunks(repeat("规", 1000), true).size());
    assertThrows(
        IllegalArgumentException.class, () -> KnowledgeText.chunks(repeat("规", 1001), true));
  }

  @Test
  void paragraphsNormalizeLineEndingsWithoutBreakingSurrogatePairs() {
    assertEquals(
        Collections.singletonList("第一段\n\n第二段"), KnowledgeText.chunks(" 第一段\r\n\r\n第二段 ", false));
    String longText = "A" + repeat("😀", 900);
    List<String> chunks = KnowledgeText.chunks(longText, false);
    assertEquals(longText, String.join("", chunks));
    assertTrue(
        chunks.stream()
            .allMatch(
                text ->
                    text.length() <= 800
                        && !Character.isHighSurrogate(text.charAt(text.length() - 1))));
    assertThrows(IllegalArgumentException.class, () -> KnowledgeText.chunks(" \n ", false));
  }

  @Test
  void tokenizerAndRankerFindLiteralEvidenceWithoutInventingSemanticMatches() {
    assertEquals(Arrays.asList("周末", "末预", "预约", "api", "12"), KnowledgeText.tokens("周末预约 API 12"));
    KnowledgeChunk parking = chunk(1, "停车", "提供地下停车场");
    KnowledgeChunk weekend = chunk(2, "周末预约", "周末无需预约，节假日例外");
    assertEquals(
        2L, KnowledgeRanker.rank("周末预约", Arrays.asList(parking, weekend)).get(0).getChunkId());
    assertTrue(KnowledgeRanker.rank("不存在的火星信息", Collections.singletonList(parking)).isEmpty());
  }

  @Test
  void unscopedQueryOnlyAsksMapperForPlatformKnowledge() {
    KnowledgeChunk found = chunk(1, "领取进度", "领取请求需要查询进度");
    when(mapper.selectCandidates(null, null, null, 201))
        .thenReturn(Collections.singletonList(found));
    when(mapper.recheckCurrentChunks(Collections.singletonList(1L), null, null, null))
        .thenReturn(Collections.singletonList(found));
    Result<Map<String, Object>> result = service.search(query("领取进度"));
    assertTrue(result.getSuccess());
    assertEquals("PLATFORM_ONLY", result.getData().get("scope"));
    assertEquals(true, result.getData().get("matched"));
    verify(mapper).selectCandidates(null, null, null, 201);
  }

  @Test
  void changedVersionIsDroppedBeforeEvidenceIsReturned() {
    KnowledgeChunk stale = chunk(1, "周末预约", "周末无需预约");
    when(mapper.selectCandidates(null, null, null, 201))
        .thenReturn(Collections.singletonList(stale));
    when(mapper.recheckCurrentChunks(Collections.singletonList(1L), null, null, null))
        .thenReturn(Collections.emptyList());
    Result<Map<String, Object>> result = service.search(query("周末预约"));
    assertEquals(false, result.getData().get("matched"));
    assertEquals(1, result.getData().get("staleEvidenceDropped"));
    assertTrue(((List<?>) result.getData().get("evidence")).isEmpty());
  }

  @Test
  void invalidScopeOrQueryCannotReadKnowledge() {
    assertFalse(service.search(query(" ")).getSuccess());
    KnowledgeSearchRequest tooMany = query("规则查询");
    tooMany.setLimit(6);
    assertFalse(service.search(tooMany).getSuccess());
    Voucher voucher = new Voucher().setId(1L).setShopId(2L).setStatus(1);
    when(vouchers.getById(1L)).thenReturn(voucher);
    KnowledgeSearchRequest mismatch = query("周末使用");
    mismatch.setShopId(3L);
    mismatch.setVoucherId(1L);
    assertFalse(service.search(mismatch).getSuccess());
    verifyNoInteractions(mapper);
  }

  @Test
  void inventedVoucherRulesCannotBePublishedAsBusinessFacts() {
    when(shops.getById(1L)).thenReturn(new Shop().setId(1L));
    when(vouchers.getById(2L))
        .thenReturn(new Voucher().setId(2L).setShopId(1L).setStatus(1).setRules("仅工作日可用"));
    KnowledgePublishRequest request = new KnowledgePublishRequest();
    request.setSourceKey("voucher-2-rules");
    request.setKind("VOUCHER_RULE");
    request.setShopId(1L);
    request.setVoucherId(2L);
    request.setExpectedVersion(0);
    request.setTitle("优惠券规则");
    request.setContent("周末随时可用");
    assertFalse(service.publish(request).getSuccess());
    verifyNoInteractions(mapper, transactions);
  }
}

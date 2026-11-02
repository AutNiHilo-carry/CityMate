package com.hmdp.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.hmdp.dto.KnowledgePublishRequest;
import com.hmdp.dto.KnowledgeSearchRequest;
import com.hmdp.dto.Result;
import com.hmdp.entity.KnowledgeChunk;
import com.hmdp.entity.Voucher;
import com.hmdp.knowledge.KnowledgeRanker;
import com.hmdp.knowledge.KnowledgeText;
import com.hmdp.mapper.KnowledgeMapper;
import com.hmdp.service.IKnowledgeService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 公开知识检索基线；当前没有向量、模型生成或业务资格判断。 */
@Service
public class KnowledgeServiceImpl implements IKnowledgeService {
  private final KnowledgeMapper mapper;
  private final TransactionTemplate transactions;
  private final IShopService shops;
  private final IVoucherService vouchers;

  public KnowledgeServiceImpl(
      KnowledgeMapper mapper,
      TransactionTemplate transactions,
      IShopService shops,
      IVoucherService vouchers) {
    this.mapper = mapper;
    this.transactions = transactions;
    this.shops = shops;
    this.vouchers = vouchers;
  }

  @Override
  public Result<Map<String, Object>> publish(KnowledgePublishRequest request) {
    List<String> chunks;
    String ruleFingerprint;
    try {
      ruleFingerprint = validatePublication(request);
      chunks = KnowledgeText.chunks(request.getContent(), "VOUCHER_RULE".equals(request.getKind()));
    } catch (IllegalArgumentException invalid) {
      return Result.fail(invalid.getMessage());
    }
    return transactions.execute(
        tx -> {
          if (request.getExpectedVersion() == 0) {
            mapper.ensureSource(request.getSourceKey());
          }
          Map<String, Object> current = mapper.lockSource(request.getSourceKey());
          if (current == null
              || ((Number) current.get("current_version")).intValue()
                  != request.getExpectedVersion()) {
            return Result.fail("来源版本已变化，请重新读取后发布");
          }
          int version = request.getExpectedVersion() + 1;
          mapper.insertRevision(
              request.getSourceKey(),
              version,
              request.getTitle(),
              request.getKind(),
              request.getShopId(),
              request.getVoucherId(),
              request.getContent(),
              ruleFingerprint,
              request.getValidFrom() == null ? LocalDateTime.now() : request.getValidFrom(),
              request.getValidUntil());
          for (int index = 0; index < chunks.size(); index++) {
            mapper.insertChunk(request.getSourceKey(), version, index, chunks.get(index));
          }
          mapper.activate(request.getSourceKey(), version);
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("sourceKey", request.getSourceKey());
          result.put("version", version);
          result.put("chunks", chunks.size());
          return Result.ok(result);
        });
  }

  private String validatePublication(KnowledgePublishRequest request) {
    if (request == null
        || !sourceKey(request.getSourceKey())
        || request.getExpectedVersion() == null
        || request.getExpectedVersion() < 0
        || request.getExpectedVersion() >= 1000000000
        || request.getTitle() == null
        || request.getTitle().trim().isEmpty()
        || request.getTitle().length() > 160
        || request.getContent() == null
        || request.getContent().trim().isEmpty()
        || request.getContent().length() > 12000
        || request.getContent().indexOf('\0') >= 0) {
      throw new IllegalArgumentException("来源、版本、标题或正文无效");
    }
    if (!Arrays.asList("PLATFORM_FAQ", "SHOP_SERVICE", "VOUCHER_RULE")
        .contains(request.getKind())) {
      throw new IllegalArgumentException("不支持的公开知识类型");
    }
    LocalDateTime from =
        request.getValidFrom() == null ? LocalDateTime.now() : request.getValidFrom();
    if (request.getValidUntil() != null && !request.getValidUntil().isAfter(from)) {
      throw new IllegalArgumentException("失效时间必须晚于生效时间");
    }
    if ("PLATFORM_FAQ".equals(request.getKind())) {
      if (request.getShopId() != null || request.getVoucherId() != null) {
        throw new IllegalArgumentException("平台 FAQ 不绑定店铺或优惠券");
      }
    } else {
      if (request.getShopId() == null
          || request.getShopId() <= 0
          || shops.getById(request.getShopId()) == null) {
        throw new IllegalArgumentException("关联店铺不存在");
      }
      if ("SHOP_SERVICE".equals(request.getKind()) && request.getVoucherId() != null) {
        throw new IllegalArgumentException("商户说明不能绑定优惠券");
      }
      if ("VOUCHER_RULE".equals(request.getKind())) {
        Voucher voucher =
            request.getVoucherId() == null ? null : vouchers.getById(request.getVoucherId());
        if (voucher == null
            || !request.getShopId().equals(voucher.getShopId())
            || !Integer.valueOf(1).equals(voucher.getStatus())) {
          throw new IllegalArgumentException("优惠券不存在、已下架或不属于当前店铺");
        }
        if (!normalize(request.getContent()).equals(normalize(voucher.getRules()))) {
          throw new IllegalArgumentException("优惠券规则必须与当前业务规则原文一致");
        }
        return fingerprint(voucher);
      }
    }
    return null;
  }

  @Override
  public Result<Map<String, Object>> withdraw(String sourceKey, Integer expectedVersion) {
    if (!sourceKey(sourceKey) || expectedVersion == null || expectedVersion <= 0) {
      return Result.fail("来源或版本无效");
    }
    return transactions.execute(
        tx -> {
          Map<String, Object> current = mapper.lockSource(sourceKey);
          if (current == null
              || ((Number) current.get("current_version")).intValue() != expectedVersion) {
            return Result.fail("来源版本已变化，请重新读取后撤回");
          }
          mapper.withdraw(sourceKey);
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("sourceKey", sourceKey);
          result.put("version", expectedVersion + 1);
          result.put("enabled", false);
          return Result.ok(result);
        });
  }

  @Override
  public Result<Map<String, Object>> search(KnowledgeSearchRequest request) {
    if (request == null
        || request.getQuery() == null
        || request.getQuery().trim().length() < 2
        || request.getQuery().length() > 200
        || request.getLimit() == null
        || request.getLimit() < 1
        || request.getLimit() > 5
        || (request.getShopId() != null && request.getShopId() <= 0)
        || (request.getVoucherId() != null && request.getVoucherId() <= 0)) {
      return Result.fail("问题长度需为 2～200，limit 为 1～5，业务 ID 必须为正数");
    }
    Long shopId = request.getShopId();
    Long voucherId = request.getVoucherId();
    String ruleFingerprint = null;
    if (voucherId != null) {
      Voucher voucher = vouchers.getById(voucherId);
      if (voucher == null
          || !Integer.valueOf(1).equals(voucher.getStatus())
          || (shopId != null && !shopId.equals(voucher.getShopId()))) {
        return Result.fail("优惠券不存在、已下架或与店铺不匹配");
      }
      shopId = voucher.getShopId();
      ruleFingerprint = fingerprint(voucher);
    }
    List<KnowledgeChunk> candidates =
        mapper.selectCandidates(shopId, voucherId, ruleFingerprint, 201);
    boolean truncated = candidates.size() > 200;
    if (truncated) {
      candidates = candidates.subList(0, 200);
    }
    List<KnowledgeChunk> ranked = KnowledgeRanker.rank(request.getQuery(), candidates);
    List<KnowledgeChunk> shortlist = ranked.subList(0, Math.min(30, ranked.size()));
    Map<Long, KnowledgeChunk> live = new HashMap<>();
    if (!shortlist.isEmpty()) {
      if (voucherId != null) {
        Voucher current = vouchers.getById(voucherId);
        if (current == null
            || !Integer.valueOf(1).equals(current.getStatus())
            || !shopId.equals(current.getShopId())) {
          return Result.fail("优惠券信息已变化，请重新查询");
        }
        ruleFingerprint = fingerprint(current);
      }
      mapper
          .recheckCurrentChunks(
              shortlist.stream().map(KnowledgeChunk::getChunkId).collect(Collectors.toList()),
              shopId,
              voucherId,
              ruleFingerprint)
          .forEach(chunk -> live.put(chunk.getChunkId(), chunk));
    }
    List<Map<String, Object>> evidence = new ArrayList<>();
    for (KnowledgeChunk candidate : shortlist) {
      KnowledgeChunk current = live.get(candidate.getChunkId());
      if (current != null && evidence.size() < request.getLimit()) {
        evidence.add(evidence(current, evidence.size() + 1));
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("mode", "KEYWORD_BM25_RETRIEVAL_ONLY");
    result.put("matched", !evidence.isEmpty());
    result.put("scope", shopId == null ? "PLATFORM_ONLY" : "SCOPED_PUBLIC_KNOWLEDGE");
    result.put("candidateTruncated", truncated);
    result.put("staleEvidenceDropped", shortlist.size() - live.size());
    result.put("evidence", evidence);
    result.put(
        "note", evidence.isEmpty() ? "未找到明确依据，不能推断商户规则" : "以下是公开原文证据，不是模型答案；库存、价格、资格和订单状态需实时查询");
    return Result.ok(result);
  }

  private Map<String, Object> evidence(KnowledgeChunk chunk, int citation) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("citation", citation);
    result.put("chunkId", chunk.getChunkId().toString());
    result.put("sourceKey", chunk.getSourceKey());
    result.put("version", chunk.getVersion());
    result.put("title", chunk.getTitle());
    result.put("kind", chunk.getKind());
    result.put("text", chunk.getContent());
    result.put(
        "sourceUrl",
        "/api/knowledge/sources/" + chunk.getSourceKey() + "?version=" + chunk.getVersion());
    return result;
  }

  @Override
  public Result<Map<String, Object>> source(String sourceKey, Integer version) {
    if (!sourceKey(sourceKey) || (version != null && version <= 0)) {
      return Result.fail("来源或版本无效");
    }
    Map<String, Object> source = mapper.selectCurrentSource(sourceKey, version);
    if (source != null && "VOUCHER_RULE".equals(source.get("kind"))) {
      Voucher current = vouchers.getById(((Number) source.get("voucher_id")).longValue());
      if (current == null
          || !Integer.valueOf(1).equals(current.getStatus())
          || !current.getShopId().equals(((Number) source.get("shop_id")).longValue())
          || !fingerprint(current).equals(source.get("rule_fingerprint"))) {
        return Result.fail("业务规则已变化，需重新发布知识来源");
      }
    }
    if (source != null) {
      source.remove("rule_fingerprint");
    }
    return source == null ? Result.fail("来源已更新、撤回或不在生效时间内") : Result.ok(source);
  }

  private static boolean sourceKey(String sourceKey) {
    return sourceKey != null && sourceKey.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
  }

  private static String normalize(String text) {
    return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
  }

  private static String fingerprint(Voucher voucher) {
    return DigestUtil.sha256Hex(normalize(voucher.getRules()));
  }
}

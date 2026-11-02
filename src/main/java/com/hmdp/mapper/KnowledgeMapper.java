package com.hmdp.mapper;

import com.hmdp.entity.KnowledgeChunk;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface KnowledgeMapper {
  int ensureSource(@Param("sourceKey") String sourceKey);

  Map<String, Object> lockSource(@Param("sourceKey") String sourceKey);

  int insertRevision(
      @Param("sourceKey") String sourceKey,
      @Param("version") Integer version,
      @Param("title") String title,
      @Param("kind") String kind,
      @Param("shopId") Long shopId,
      @Param("voucherId") Long voucherId,
      @Param("content") String content,
      @Param("ruleFingerprint") String ruleFingerprint,
      @Param("validFrom") LocalDateTime validFrom,
      @Param("validUntil") LocalDateTime validUntil);

  int insertChunk(
      @Param("sourceKey") String sourceKey,
      @Param("version") Integer version,
      @Param("chunkNo") Integer chunkNo,
      @Param("content") String content);

  int activate(@Param("sourceKey") String sourceKey, @Param("version") Integer version);

  int withdraw(@Param("sourceKey") String sourceKey);

  List<KnowledgeChunk> selectCandidates(
      @Param("shopId") Long shopId,
      @Param("voucherId") Long voucherId,
      @Param("ruleFingerprint") String ruleFingerprint,
      @Param("limit") Integer limit);

  List<KnowledgeChunk> recheckCurrentChunks(
      @Param("ids") List<Long> ids,
      @Param("shopId") Long shopId,
      @Param("voucherId") Long voucherId,
      @Param("ruleFingerprint") String ruleFingerprint);

  Map<String, Object> selectCurrentSource(
      @Param("sourceKey") String sourceKey, @Param("version") Integer version);
}

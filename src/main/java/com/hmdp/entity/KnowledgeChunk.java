package com.hmdp.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 包含来源信息的只读检索候选，chunkId 和 version 共同标识原文。 */
@Data
public class KnowledgeChunk {
  private Long chunkId;
  private String sourceKey;
  private Integer version;
  private Integer chunkNo;
  private String title;
  private String kind;
  private Long shopId;
  private Long voucherId;
  private String content;
  private LocalDateTime validFrom;
  private LocalDateTime validUntil;
}

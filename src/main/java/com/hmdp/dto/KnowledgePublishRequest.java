package com.hmdp.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** 只用于管理员发布公开知识，不接收模型或普通用户上传内容。 */
@Data
public class KnowledgePublishRequest {
  private String sourceKey;
  private String title;
  private String kind;
  private Long shopId;
  private Long voucherId;
  private String content;
  private Integer expectedVersion;
  private LocalDateTime validFrom;
  private LocalDateTime validUntil;
}

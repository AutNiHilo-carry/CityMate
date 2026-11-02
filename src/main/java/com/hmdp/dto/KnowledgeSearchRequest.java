package com.hmdp.dto;

import lombok.Data;

@Data
public class KnowledgeSearchRequest {
  private String query;
  private Long shopId;
  private Long voucherId;
  private Integer limit = 3;
}

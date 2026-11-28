package com.hmdp.dto;

import lombok.Data;

@Data
public class AgentQueryRequest {
  private String query;
  private Long shopId;
  private Long voucherId;
  private Double x;
  private Double y;
}

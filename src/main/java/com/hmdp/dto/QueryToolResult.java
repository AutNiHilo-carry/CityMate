package com.hmdp.dto;

import java.time.Instant;
import java.util.Map;
import lombok.Getter;

/** 工具执行状态与业务状态分开：OK 不代表领取、履约或付款成功。 */
@Getter
public final class QueryToolResult {
  public enum Status {
    OK,
    INVALID_ARGUMENT,
    UNAUTHENTICATED,
    BUSINESS_REJECTED,
    TIMEOUT,
    BUSY,
    UNAVAILABLE
  }

  private final String tool;
  private final Status status;
  private final Map<String, Object> data;
  private final Long total;
  private final String message;
  private final String observedAt;

  private QueryToolResult(
      String tool, Status status, Map<String, Object> data, Long total, String message) {
    this.tool = tool;
    this.status = status;
    this.data = data;
    this.total = total;
    this.message = message;
    this.observedAt = Instant.now().toString();
  }

  public static QueryToolResult ok(String tool, Map<String, Object> data, Long total) {
    return new QueryToolResult(tool, Status.OK, data, total, null);
  }

  public static QueryToolResult failure(String tool, Status status, String message) {
    return new QueryToolResult(tool, status, null, null, message);
  }
}

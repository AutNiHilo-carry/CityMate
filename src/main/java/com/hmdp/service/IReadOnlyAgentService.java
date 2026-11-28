package com.hmdp.service;

import com.hmdp.dto.AgentQueryRequest;
import com.hmdp.dto.Result;
import java.util.Map;

/** Authenticated, bounded query orchestration; no business writes. */
public interface IReadOnlyAgentService {
  boolean allowClient(String address);

  Result<Map<String, Object>> query(AgentQueryRequest request);
}

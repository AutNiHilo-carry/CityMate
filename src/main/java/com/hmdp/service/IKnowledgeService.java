package com.hmdp.service;

import com.hmdp.dto.KnowledgePublishRequest;
import com.hmdp.dto.KnowledgeSearchRequest;
import com.hmdp.dto.Result;
import java.util.Map;

public interface IKnowledgeService {
  Result<Map<String, Object>> publish(KnowledgePublishRequest request);

  Result<Map<String, Object>> withdraw(String sourceKey, Integer expectedVersion);

  Result<Map<String, Object>> search(KnowledgeSearchRequest request);

  Result<Map<String, Object>> source(String sourceKey, Integer version);
}

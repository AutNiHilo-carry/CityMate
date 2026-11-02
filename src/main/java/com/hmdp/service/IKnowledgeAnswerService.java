package com.hmdp.service;

import com.hmdp.dto.KnowledgeSearchRequest;
import com.hmdp.dto.Result;
import java.util.Map;

public interface IKnowledgeAnswerService {
  Result<Map<String, Object>> answer(KnowledgeSearchRequest request);
}

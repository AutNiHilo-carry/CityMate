package com.hmdp.controller;

import com.hmdp.dto.KnowledgePublishRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IKnowledgeService;
import java.util.Map;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 继续由原有 /ops/ 管理员白名单和审计拦截保护，不暴露给 Agent 工具。 */
@RestController
@RequestMapping("/ops/knowledge")
public class KnowledgeAdminController {
  @Resource
  private IKnowledgeService knowledge;

  @PostMapping
  public Result<Map<String, Object>> publish(@RequestBody KnowledgePublishRequest request) {
    return knowledge.publish(request);
  }

  @PostMapping("/{sourceKey}/withdraw")
  public Result<Map<String, Object>> withdraw(
      @PathVariable String sourceKey, @RequestParam Integer expectedVersion) {
    return knowledge.withdraw(sourceKey, expectedVersion);
  }
}

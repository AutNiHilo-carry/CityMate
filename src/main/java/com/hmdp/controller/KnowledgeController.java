package com.hmdp.controller;

import com.hmdp.dto.KnowledgeSearchRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IKnowledgeService;
import java.util.Map;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {
  @Resource
  private IKnowledgeService knowledge;

  @GetMapping("/search")
  public Result<Map<String, Object>> search(KnowledgeSearchRequest request) {
    return knowledge.search(request);
  }

  @GetMapping("/sources/{sourceKey}")
  public Result<Map<String, Object>> source(
      @PathVariable String sourceKey, @RequestParam(required = false) Integer version) {
    return knowledge.source(sourceKey, version);
  }
}

package com.hmdp.controller;

import com.hmdp.dto.KnowledgeSearchRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IKnowledgeAnswerService;
import java.util.Map;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeAnswerController {
  @Resource
  private IKnowledgeAnswerService answers;

  @PostMapping("/answer")
  public Result<Map<String, Object>> answer(@RequestBody KnowledgeSearchRequest request) {
    return answers.answer(request);
  }
}

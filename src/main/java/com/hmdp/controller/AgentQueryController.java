package com.hmdp.controller;

import com.hmdp.dto.AgentQueryRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IReadOnlyAgentService;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentQueryController {
  private final IReadOnlyAgentService agent;

  public AgentQueryController(IReadOnlyAgentService agent) {
    this.agent = agent;
  }

  @PostMapping("/agent/query")
  public ResponseEntity<?> query(@RequestBody AgentQueryRequest body, HttpServletRequest request) {
    if (!agent.allowClient(request.getRemoteAddr())) {
      return ResponseEntity.status(429).body(Result.fail("查询较多，请稍后重试"));
    }
    return ResponseEntity.ok(agent.query(body));
  }
}

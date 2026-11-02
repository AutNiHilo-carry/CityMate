package com.hmdp.service;

import com.hmdp.dto.QueryToolResult;
import java.util.Map;
import java.util.Set;

/** 供后续单 Agent 调用的内部只读入口，不是匿名 HTTP 接口。 */
public interface IQueryToolService {
  Set<String> supportedTools();

  /** 用户身份只取调用线程已认证的 UserHolder，不接受 arguments 中的用户身份。 */
  QueryToolResult execute(String tool, Map<String, Object> arguments);
}

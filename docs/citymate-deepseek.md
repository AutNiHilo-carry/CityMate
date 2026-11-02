# CityMate：DeepSeek 规则问答

本阶段在已有 BM25 检索之上增加模型生成，保留 Java 8 / Spring Boot 2.3，不新增 SDK 或向量库。模型通过官方 `POST https://api.deepseek.com/chat/completions` 调用，默认 `deepseek-flash`，模型名可配置。接口及模型参数参考 [DeepSeek 官方 API](https://api-docs.deepseek.com/api/create-chat-completion/) 和 [模型列表](https://api-docs.deepseek.com/quick_start/pricing/)。

## 实现范围

`POST /knowledge/answer`，浏览器经 Nginx 使用 `/api/knowledge/answer`。必须登录，沿用项目 Authorization 令牌格式（不是新增一套 Bearer 登录）。模型服务的 Bearer Key 只在服务端使用。

请求与检索接口使用相同字段：

```json
{"query":"秒杀返回订单号说明已经付款了吗？","limit":3}
```

指定商户或券时附 `shopId` / `voucherId`，不能随意跨店铺检索规则。无范围只检索平台 FAQ。

流程：验证问题和业务范围 → 检索当前有效原文 → 无证据直接返回 → DeepSeek 生成结构化 JSON → 校验回答格式及引用编号 → 复查全部输入证据的版本、有效期和业务规则 → 返回回答及服务器生成的来源。

返回沿用 `Result`。`success=true` 仅表示成功处理请求，不能据此认为模型生成了答案。必须检查 `data.answered` 和 `data.status`：

| status | 含义 |
| --- | --- |
| ANSWERED | 已生成并通过结构、引用及当前来源有效性检查 |
| NO_EVIDENCE | 无匹配资料，未调用付费模型 |
| MODEL_NOT_CONFIGURED | 开关未启用或密钥缺失，返回检索原文供查看 |
| INSUFFICIENT_EVIDENCE | 模型判断资料不足，返回固定兜底 |
| STALE_EVIDENCE | 推理期间资料更新、撤回、过期或规则改变，丢弃整段生成结果 |
| INVALID_MODEL_RESPONSE | 非法 JSON、截断、空回答、无效引用或伪造链接等 |
| MODEL_TIMEOUT / MODEL_RATE_LIMITED / MODEL_UNAVAILABLE | 超时、上游限流或调用失败 |
| BUSY | 单实例两个执行名额均已占用，没有排队 |

`sources` 返回真实检索证据和来源链接，模型不负责构造 URL。每个来源包含 sourceKey、version、text、citation 等字段。前端应以纯文本渲染 answer；未来增加页面时不可将模型文本直接当 HTML 插入。

## 调用边界

- 登录用户每分钟最多 6 次，IP 每分钟最多 12 次，复用 Redis 限流。反向代理下 remoteAddr 可能是 Nginx 地址，IP 限额会成为共享限额；未无条件信任客户端 X-Forwarded-For。
- 每实例最多 2 个问答请求同时执行；连接超时最多 3 秒，模型读取按约 20 秒截止时间控制；输出最多 800 tokens，HTTP 响应最多 64 KiB。操作系统 DNS 或网络写入阻塞不构成严格的整个业务请求 20 秒 SLA。
- 不自动重试、不跟随重定向，不记录或回传密钥、提示正文、上游错误正文及 reasoning_content。
- 仅发送当前问题和公开知识，无工具权限、个人订单或对话历史。资料中的指令在系统提示中被标记为不可信，但这不能证明已完全解决提示注入或幻觉。
- `ANSWERED` 只代表结构和引用有效，不代表回答的每句话已被事实核验。上线前仍需人工标注评测，关注证据是否真正支持回答。
- 来源复查仍有并发时间窗口，不是数据库与模型共享的一致事务。实时库存、价格、资格和订单状态必须查业务接口。

## 本机配置

默认关闭，源码中无真实 Key。PowerShell 在启动项目的同一个终端设置：

```powershell
$env:CITYMATE_AI_ENABLED = 'true'
$env:DEEPSEEK_MODEL = 'deepseek-flash'
$env:DEEPSEEK_API_KEY = [System.Net.NetworkCredential]::new('', (Read-Host '输入 DeepSeek API Key' -AsSecureString)).Password
```

该写法避免将明文 Key 写进命令历史；环境变量和容器配置仍应仅对本机可信用户开放。不要把 Key 发到聊天、截图、日志或 Git。

然后按原启动方式构建并启动。`dev/docker-compose.yml` 已传递以上三个环境变量；仅在宿主设置变量后重启旧容器不会更新其环境，需重新创建 Java 服务。保留原启动所用管理员等环境变量，在项目根目录执行：

```powershell
docker compose -p hm-dianping -f dev/docker-compose.yml -f dev/docker-compose.prebuilt.yml up -d --no-deps --force-recreate java
```

此命令使用 `target/hm-dianping-0.0.1-SNAPSHOT.jar`，必须先按项目原构建命令更新 JAR。不要为了启用 AI 删除数据卷。也可在 IDE Run Configuration 配置这三个环境变量后启动。

检查环境变量时仅检查是否存在，不输出值。不要将 `docker compose config` 或 `docker inspect` 的完整环境输出贴到报告中。

## 测试与当前状态

新增 KnowledgeAnswerTest（服务、来源变更、限流与并发）和 DeepSeekClientTest（本机 HTTP 模拟：请求参数、超时、响应过大、限流、截断和重定向），无真实 API 消费。验证结果另见 `citymate-deepseek-verification.json`（实际运行后生成）。

已接入基础 AI 聊天页面；当前未接 SSE、Embedding、模型工具调用或确认领券。真实模型问答需本机配置密钥并启动环境后另行验收；不能把本地模拟测试当成真实模型质量评估。

2026-11-02：本机 JDK 21 按 Java 8 目标编译；17 项新增测试及 27 项选定回归全部通过，共 44 项。Checkstyle 与 package 通过。旧 MyBatis 回归在本机运行需为测试 JVM 开放 java.util/java.lang 模块；生产 Java 8 不使用这些参数。构建日志：target/citymate-deepseek-tests.log。尚未启动 Docker 或调用真实模型。

## 推荐：使用本机 .env 文件填写 Key

项目根目录 `.env` 已加入 `.gitignore`，`.env.example` 只保留无密钥模板。在 VS Code 打开 `.env`，将 Key 填在 `DEEPSEEK_API_KEY=` 后面，保存文件。Key 会明文显示，填写后不要截图或分享此文件。

然后在项目根目录运行 `.scriptsstart-citymate-ai.ps1`。脚本只重新创建 Java 容器，明确读取根目录 `.env`，并临时移除终端里同名的旧 AI 环境变量，避免它们覆盖文件；执行后恢复终端原值。脚本不输出 Key、不删除数据卷。

不要使用 `deploy/.env` 配置本地 dev 容器，那是另一套部署配置。不要将真实 Key 填入 `.env.example`。本机 `.env` 是明文文件，Git 忽略不等于加密存储。

## 2026-11-02 真实模型联调结果

使用 Git 忽略的本机 .env 更新 Java 容器后，6 项真实 HTTP 检查通过：健康状态、未登录拦截、无证据兜底、真实模型回答、服务器来源返回、当前来源版本查看。问题为领取进度中 SUCCESS 表示已经付款了吗，来源 platform-claim-progress，回答状态 ANSWERED。单次接口耗时约 1.5 秒，仅是一次功能观测，不代表平均耗时、P99 或回答准确率。详见 [真实联调记录](citymate-deepseek-live-verification.json)。此前构建阶段尚未调用模型的记录已由本次联调补充；系统性质量评测、聊天页面与 Agent 工具循环仍未完成。


基础聊天页面现已接入，详见 [页面说明与验证](citymate-assistant-page.md)。页面使用完整响应，尚未接入 SSE 或跨轮上下文。

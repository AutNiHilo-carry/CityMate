# CityMate 公开知识检索：RAG 的检索层

当前实现的是可运行的检索层：管理员发布原文 → 切分 → MySQL 保存来源和版本 → 按业务范围筛选 → Java BM25 关键词排序 → 再次校验有效性 → 返回原文与出处。没有调用 Embedding 或大模型，没有生成答案，也没有新增 AI 聊天界面。

## 接口

| 接口 | 权限与作用 |
| --- | --- |
| `GET /knowledge/search?query=领取进度` | 公开只读，按 IP 每分钟最多 60 次 |
| `GET /knowledge/search?query=周末&shopId=1&voucherId=1&limit=3` | 按店铺和优惠券范围检索；limit 1～5 |
| `GET /knowledge/sources/{sourceKey}?version=2` | 查看该来源当前有效版本的原文，旧版本、撤回和过期来源拒绝作为依据 |
| `POST /ops/knowledge` | 现有管理员白名单保护，发布或更新公开资料 |
| `POST /ops/knowledge/{sourceKey}/withdraw?expectedVersion=2` | 管理员撤回，继续沿用原有运维审计 |

浏览器经 Nginx 访问时加 `/api`。例如 [查看“领取进度”的检索结果](http://127.0.0.1:8080/api/knowledge/search?query=%E9%A2%86%E5%8F%96%E8%BF%9B%E5%BA%A6)。查询失败依旧使用项目 Result 响应，不把 HTTP 200 当成业务成功。

## 数据与发布

新增 Flyway V10，仅创建三张知识表，不重建原有业务表：

- `tb_knowledge_source`：来源标识、当前控制版本、是否启用。
- `tb_knowledge_revision`：不可变的历史原文、类型、业务范围、生效窗口及规则摘要。
- `tb_knowledge_chunk`：每个已发布正文版本的原文片段。

发布示例，第一次 expectedVersion 为 0：

```json
{
  "sourceKey": "platform-claim-progress",
  "expectedVersion": 0,
  "title": "领取进度与订单状态",
  "kind": "PLATFORM_FAQ",
  "content": "秒杀返回订单号表示已受理。SUCCESS表示订单已落库，不表示已经付款。"
}
```

更新时提交当前控制版本；服务在事务中锁定来源，原文、全部片段和版本切换一起提交。两个相同 expectedVersion 的并发发布只有一个成功。

撤回也会递增控制版本，阻止迟到的旧发布请求重新启用资料。撤回返回的新版本是控制标记，没有对应的可检索正文；重新发布需使用撤回响应的新版本，因此正文版本编号可以出现间隔。历史原文保留，但公开引用只允许当前有效正文，不能拿历史版本冒充当前规则。

## 三类知识与隔离

- `PLATFORM_FAQ` 不绑定商户或券。未提供业务范围时仅检索此类资料，不能自动跨店搜索规则。
- `SHOP_SERVICE` 必须绑定真实存在的 shopId，不能绑定 voucherId。
- `VOUCHER_RULE` 必须绑定该店真实有效的券，正文必须与数据库当前 rules 原文一致。保存规则 SHA-256；查询和来源查看时复查业务规则，规则已变化则旧证据失效，需重新发布。

给定券 ID 时，服务读取真实券元数据确定店铺；同时传了错误的店铺 ID 会被拒绝。限定店铺的检索允许补充该店说明和平台 FAQ，但不会混入其他店的资料。

商户说明和 FAQ 需管理员根据实际业务维护，系统不替商户编造预约、停车或周末政策。它们只保存公开资料，不用于存放个人订单、账号凭证或内部运维信息。

## 切分、检索与证据

- 正文最多 12000 字符，标题最多 160 字符。
- 优惠券规则最多 1000 字符，并完整保留为一个片段，避免把适用条件与例外拆开。
- 长商户说明和 FAQ 按段落组合成最多 800 字符的片段；超长段落按长度切分，避免截断 UTF-16 代理对。长说明仍可能在片段边界丢失跨段关系，后续生成时需考虑相邻上下文。
- 中文使用相邻双字，英文和数字使用词元，Java 内存中计算 BM25。当前没有向量索引、语义召回、RRF 或重排序模型，不能宣称已实现混合检索。
- 首先只从当前启用、有效期内、业务范围匹配的片段中取最多 200 条候选。候选有截断时返回 candidateTruncated=true。这是小规模基线，规模扩大后需要索引或更好的候选召回；没有命中不代表整个数据库没有相关规则。
- 对排名靠前的最多 30 条候选再做一次当前版本与业务规则摘要校验，再返回最多 5 条原文证据。检索期间变更的资料会被丢弃。
- 结果包含 citation、chunkId、sourceKey、version、title、kind、text、sourceUrl。来源链接由服务器按真实标识生成，不是模型编造的链接。
- 返回 matched=false 时不生成答案或推测商户政策。BM25 分数也不是答案正确率，因此不把它显示成置信度百分比。

这不是跨数据库和请求的全局一致快照。之后接模型生成时，仍需在使用证据和展示引用时考虑版本变化；文档文本也必须作为不可信数据处理，不能让其中的指令覆盖系统约束。

## 与现有查询工具分工

库存、价格、资格和订单状态仍走现有 Service/查询工具。公开知识只能解释规则，不能据此承诺还有库存、符合个人资格、已领取或已付款。当前也没有把管理发布接口暴露为 Agent 工具。

## 本次验证与复验

结果见 [知识模块验证记录](citymate-knowledge-verification.json) 与 [HTTP 记录](citymate-knowledge-http.json)。覆盖切分、词元排序、未匹配、店铺隔离、无范围只查 FAQ、更新与撤回、生效窗口、规则摘要失效、并发版本和管理员权限。没有重新压测，也没有测出模型回答准确率或语义检索召回率。

本地已保留平台 FAQ `platform-claim-progress`，其内容来自项目真实订单状态语义。未自动生成任何商户规则。

可用现有隔离测试命令复验；集成测试写入前检查数据库必须为 `hmdp_optimization_test`：

```bash
docker compose -p hmdp-optimization-test -f dev/docker-compose.test.yml run --rm test mvn -B -Dtest=KnowledgeUnitTest,KnowledgeAccessTest,KnowledgeIntegrationTest test
```

本地 HTTP 复验脚本通过正常验证码登录，不保存验证码或 Token；需要两个已有演示账号，其中一个已配置为管理员：

```powershell
python scripts/verify-knowledge.py --admin-phone <演示管理员手机号> --ordinary-phone <普通演示手机号> --output docs/citymate-knowledge-http-repeat.json
```

后续在这套来源、版本和评估基线上接 Embedding/向量召回，再接带引用的模型生成，分别与当前关键词基线做对照。

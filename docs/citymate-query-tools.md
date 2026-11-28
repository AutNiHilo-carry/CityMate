# CityMate 查询工具（第一阶段）

本页保留第一阶段的内部 Java 查询入口 `IQueryToolService` 设计。后续已通过 [Phase 7 单 Agent](PHASE7_READ_ONLY_AGENT.md) 接入模型、RAG 和助手页面；SSE 与 AI 领券写操作仍未实现。

## 四个工具

| 名称 | 参数 | 行为 |
| --- | --- | --- |
| `searchShops` | 必填 `typeId`；可选 keyword、area、maxPrice、minScore、hasVoucher、x、y、page、size、sort | 复用商户组合搜索，最多返回 20 条 |
| `listShopVouchers` | 必填 `shopId`；可选 limit，默认 10、最大 20 | 复用券目录和实时活动查询，对选中的券批量复核当前元数据与金额 |
| `listMyOrders` | 可选 page、heldOnly，默认 1、false | 查询当前已登录用户的订单，每页最多 20 条 |
| `getClaimStatus` | 必填 `orderId` | 查询当前用户的秒杀请求进度，其他用户不可读取 |

`typeId` 需要由调用方根据真实分类确定；附近查询必须提供真实的经纬度，工具不猜测用户位置。价格筛选 maxPrice 使用元，评分 minScore 使用十分制整数（4.5 分传 45）；输出金额 payCents 使用分。ID 在输出中统一使用字符串，避免 JavaScript 大整数精度损失。传入 ID 可以用整数或十进制整数字符串，不能用浮点数。

参数采用白名单校验，`userId`、任意 SQL、任意 Service 方法名等不能作为工具参数传入。布尔参数只接受真正的 Boolean，不把字符串 "false" 当作布尔值。

## 调用示例

在后续 Agent 服务中注入 `IQueryToolService`，使用当前认证请求的身份：

```java
Map<String, Object> arguments = new HashMap<>();
arguments.put("typeId", "1");
arguments.put("maxPrice", 50);
arguments.put("size", 5);
QueryToolResult result = queryTools.execute("searchShops", arguments);
```

查询本人订单：

```java
QueryToolResult result = queryTools.execute("listMyOrders", Collections.emptyMap());
```

调用方需已通过项目原有认证链路，`UserHolder` 中保存服务端恢复的身份。模型参数不能创建该身份；未登录调用个人查询时直接返回 UNAUTHENTICATED，不访问业务 Service。

## 执行与输出边界

- 工具执行状态为 OK、INVALID_ARGUMENT、UNAUTHENTICATED、BUSINESS_REJECTED、TIMEOUT、BUSY 或 UNAVAILABLE。OK 只表示查询完成，不表示领取或付款成功。
- 复用已有业务 Service 和 Mapper，不重复实现扣库存、建单、登录或权限逻辑。
- 后台查询使用独立的 2 线程池、16 个排队槽位。队列满时返回 BUSY，不无限创建线程；等待预算默认 3000 ms，通过 `hmdp.agent.query-tools.timeout-ms` 设置，限制在 50～10000 ms。
- 超时会取消 Future、请求中断并移除仍在队列中的任务，但不能强制终止不响应中断的 JDBC/网络调用；底层超时仍需单独配置。本阶段只有只读查询，不自动重试，不宣称“返回超时即后端完全停止”。
- 执行前只复制认证用户 ID，在工作线程中设置身份和请求缓存作用域；结束或异常时清理。调用线程自己的身份不被覆盖。
- 只输出业务字段白名单，不输出用户 ID、令牌、内部异常、预约失败原因或 SQL。工具结果有服务器完成时间 observedAt，但多次读取并非一个全局一致快照，库存与价格在查询后仍可能变化。
- 领取状态 PROCESSING 是已受理未确认；SUCCESS 是正式订单已落库；REVIEW 是待核对；FAILED 是履约失败。工具始终将付款状态标记为 NOT_CHECKED。
- 券目录仍复用原有缓存链路，目录新增和删除的传播遵循原有缓存机制；已选券再批量读当前元数据，过滤已下架或已迁店的券。返回 availableAtQuery 不表示已核验个人领取资格或保证库存。
- 响应条数限制不等于底层券目录查询已分页；原有目录 Service 仍可能读取该店全部券。未来大量券的场景需要单独增加业务分页，不能宣称所有数据库访问都受返回条数限制。

## 验证

本次增加 11 项工具单元测试和 3 项隔离集成测试。单元测试验证参数拒绝、身份注入、跨任务身份清理、输出脱敏、大 ID 精度、状态含义、价格复查、超时中断和满队列拒绝；集成测试使用独立数据库 `hmdp_optimization_test` 核验真实查询、缓存旧价复核、本人订单及预约归属。

完整测试结果见 [本次工具验证记录](citymate-query-tools-verification.json)。日志位于 `target/citymate-query-tools-unit.log` 和 `target/citymate-query-tools-integration.log`。这些是功能验证，不是压测。

已有隔离测试环境就绪后可执行（首次需先创建外部 Maven 缓存卷）：

```bash
docker compose -p hmdp-optimization-test -f dev/docker-compose.test.yml run --rm test mvn -B -Dtest=QueryToolServiceTest,QueryToolsIntegrationTest test
```

不要将集成测试连接到业务数据库；测试在写入前检查数据库名。

下一阶段再增加轻量 RAG；每轮 Agent 的工具调用次数、总预算和领取确认流程将在接模型及写工具阶段实现。

# CityMate：演示与证据导航

整理日期：2026-11-09。目的：用一条交易业务讲清实现、恢复责任与验证结果。配套 [简历与讲解](RESUME_AND_INTERVIEW.md)。本页为操作手册与既有证据导航，本轮未重新启动业务容器、录制视频或执行压测。

最新代码已完成 [逻辑审计与修复](LOGIC_AUDIT_2026-11-09.md)，并已 [更新本地预构建 JAR、完成 17 项 HTTP 验收](LOCAL_RELEASE_2026-11-09.md)。本人登录后的领取演示仍需按本页走查；历史性能和故障记录保留原版本范围。

## 演示前准备

1. 按 [启动教程](../wiki/02-环境搭建与启动.md) 使用自己的开发环境。若已有业务容器，先查看状态与日志，沿用既定启动方式；不要为了演示重建卷或重置数据库。预构建 JAR 配置不会自动编译源代码。
2. 检查 http://127.0.0.1:8081/actuator/health 和公开分类查询；首页能展示图片不代表 Java/数据库正常。
3. 打开 http://127.0.0.1:8080/experience.html 并登录，检查能力开关。开发环境与云端轻量配置不同；MQ/Kafka 关闭时，只展示实际启用链路，不把本地投影当作 Broker 消费。
4. 选一个当前存在的 shopId、一个当前可领取且本人未领过的 voucherId。秒杀还需管理员按 [秒杀章节](../wiki/08-秒杀Lua与RedisStream.md) 发布；缺少权限或活动不合法时换用普通券展示，不临时修改库存、身份或资格。
5. 演示截图、视频与共享文件不包含验证码、登录令牌、API Key 或数据库凭据。

## 10 分钟展示顺序

| 时间 | 实际操作 | 需要解释与保留的证据 |
| --- | --- | --- |
| 0～1 分钟 | 首页搜索商户，进入店铺详情 | shopId 与查询条件；用一句话说明本地生活业务和个人工程改造 |
| 1～3 分钟 | 体验验证页选择店铺，执行缓存采样 | 页面显示的实际命中层/耗时；热缓存不保证现场展示数据库 miss，不为演示直接清缓存 |
| 3～5 分钟 | 同页选券，确认领取并查询本人订单 | 保存 voucherId、orderId；普通券与秒杀受理链路分别说明，不能把普通券页面当作秒杀完成证据 |
| 5～6 分钟 | 重复查询/领取同一张允许复验的券，检查原订单 | 原 orderId、本人订单状态与任务进度；若页面入口限制操作，展示已有接口/报告，勿绕过权限 |
| 6～8 分钟 | 打开 Phase 6K 报告及阶段 JSON | 指出 Redis 2、Inbox 0、原 INSERT 等锁 → 杀进程后事务消失 → 重启 Inbox 1、库存仍 2 |
| 8～10 分钟 | 展示独立 JAR 对账与开发环境优化报告 | 分开解释“正确性”“响应延迟”“吞吐口径”，最后说明尚未验证的边界 |

页面操作以当前展示为准，不承诺每次都能在前端捕获瞬时 PROCESSING。云端轻量模式若没运行真实 MQ，真实 Broker 与崩溃证据通过隔离归档展示。公开浏览行为只会产生分析数据；可选 Kafka 演示以本次 eventId 的 PERSISTED 回执为准，热榜可能包含其他用户。

## 用同一 ID 串起链路

从本人登录会话中查看浏览器网络请求，无需把令牌写进文档或终端命令：

| 动作 | 代码中的接口 | 解释 |
| --- | --- | --- |
| 秒杀受理 | POST /voucher-order/seckill/{voucherId} | 返回受理订单号，不直接当作正式落库成功 |
| 原请求状态 | GET /voucher-order/requests/{orderId} | 服务端按当前登录用户校验归属 |
| 正式本人订单 | GET /voucher-order/{orderId} | 查看订单 UNPAID/PAID/CANCELLED 等生命周期状态 |
| 任务进度 | GET /experience/orders/{orderId} | 辅助观察本人任务；不等同于完整释放事件审计表 |
| 缓存采样 | GET /experience/cache?shopId={shopId} | 以实际路径结果为准，非新的压测数据 |

所有花括号都需要换成自己的真实对象。若需数据库级细节，读隔离 JSON 或在授权环境使用只读查询；演示过程不手工 ACK、重置 offset、改订单状态或注入故障。

## 六个已验证故障窗口

| 窗口 | 关键证据 | 报告 |
| --- | --- | --- |
| Stream 尚未完成数据库交接 | Pending 保留，重启按原 orderId 续跑 | [6G 两个场景](PHASE6_PROCESS_RECOVERY.md) |
| Outbox 已 SENT、订单未落库 | 重启沿用原事件完成订单，后续源重放不重复建单 | [6G 两个场景](PHASE6_PROCESS_RECOVERY.md) |
| 数据库库存已更新、订单 INSERT 未完成 | 杀进程后先核实原事务消失与库存回滚，再重启 | [6H](PHASE6_TRANSACTION_CRASH.md) |
| 订单已提交、Broker 位点未推进 | 应用停止时 ConsumerOffset 0；重启重复分支计数增加，位点到 1 | [6I](PHASE6_COMMIT_ACK_LOSS.md) |
| 关单已回数据库库存、Redis 尚未释放 | 原释放事件重启后 DONE，双库存对齐 | [6J](PHASE6_EXPIRY_CRASH.md) |
| Redis 已释放、Inbox 尚未提交 | 消费事务回滚后 Redis 标记保留，原事件重试不再回库 | [6K](PHASE6_RELEASE_GAP_CRASH.md) |

这些是选定窗口、合成订单与本机隔离环境的证据，不是全故障覆盖或线上 SLA。各阶段回归存在重复，19/15/33/45 等数量不相加为独立测试总数。

## 性能与回归证据索引

- [缓存恢复](PHASE2_CACHE_RECOVERY.md)：旧回填、失效和恢复的选定用例。
- [秒杀正确性](PHASE3_SECKILL_CORRECTNESS.md)：资格、一人一单、库存与事务断言。
- [失败补偿](PHASE4_DURABLE_COMPENSATION.md)、[超时关单](PHASE5_ORDER_EXPIRY.md)：身份校验、幂等与回库规则。
- [请求分段诊断](PHASE6_REQUEST_STAGE_PROFILE.md) → [Lua 初始化加载优化](PHASE6_LUA_LOADING_OPTIMIZATION.md)：同开发环境单对前后实验，保留 AOF always 和 NOSCRIPT 回退。
- [独立可执行 JAR 复测](PHASE6_PACKAGED_JAR_PROFILE.md)：600 次测量请求、300 单，受理 ID 与落库 ID、预约、Inbox、双库存对账；包含库存不足响应，非最大容量或稳态订单 TPS。

六个故障窗口对应的汇总 JSON 见同名报告的“证据”链接。报告定位到 runId 后读取本轮归档，避免拿旧轮次的截图解释另一次实验。

## 故障复验的使用方式

录制或现场普通页面演示不依赖再跑故障。需要复验时，在项目根目录单独执行一个脚本，等待它完成清理再运行下一个：

| 复验目标 | 脚本 | 专用项目 / 端口 |
| --- | --- | --- |
| Stream / Outbox 中断 | scripts/measure-process-recovery.ps1 | citymate-crash-p6 / 18088 |
| 库存更新后的事务中断 | scripts/measure-transaction-crash.ps1 | citymate-tx-crash-p6 / 18089 |
| 提交后消费位点丢失 | scripts/measure-commit-ack-loss.ps1 | citymate-ack-p6 / 18090 |
| 关单提交后 Redis 待释放 | scripts/measure-expiry-crash.ps1 | citymate-expiry-p6 / 18091 |
| Redis 已释放、Inbox 待提交 | scripts/measure-release-gap-crash.ps1 | citymate-release-gap-p6 / 18092 |

脚本拒绝已有同名环境/端口占用，使用独立测试库与合成数据，默认清理本轮专用容器、卷和带运行标签的镜像。若启动被拒绝，先核对遗留状态；不要替换成业务 Compose 项目来强行运行。外部图片目录、机器资源和 API Key 不随 GitHub 同步；故障脚本不需要真实模型 Key。

## 一次演示留下什么

```text
实际演示时间、代码版本/未提交修改说明：
当前启动方式、MQ/Kafka 开关：
shopId / voucherId / 原 orderId：
领取响应 / 请求状态 / 正式订单状态：
重复操作返回的 orderId：
缓存实际路径与截图位置：
引用的故障 runId 与报告：
引用性能数字的日期、环境、范围：
未演示或未通过项与原因：
```

当前文档和归档已可用于准备面试；本人完成一次页面走查、保存上述记录并能独立解释核心链路后，再定稿投递材料。真实收款、退款、核销、基础设施崩溃和多机高可用仍不作为已完成能力。

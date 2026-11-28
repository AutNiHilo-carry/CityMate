# Phase 1：CityMate 底座与贡献边界盘点

后续进展（2026-11-05）：新付费秒杀订单的超时关闭与双库存归还已完成，见 [Phase 5](PHASE5_ORDER_EXPIRY.md)。下文保留当时的阶段边界；历史无截止时间订单不自动取消。

盘点日期：2026-11-02。代码基线：`6d35444`。本轮只阅读源码、迁移、测试用例及历史报告，未执行新压测、故障注入或完整回归，未改变业务逻辑。

最新方向以 [PROJECT_DIRECTION.md](PROJECT_DIRECTION.md) 为准：高并发交易与可靠订单优先，AI/RAG 保留为 P1。下文的已有实现不等于生产故障已经全部验证。

后续进展（2026-11-05）：下文保留盘点时的历史缺口；P0-B 的持久补偿任务已实现并完成选定验证，详见 [Phase 4](PHASE4_DURABLE_COMPENSATION.md)。真实进程故障和秒杀取消仍待后续验证。

## 1. 贡献边界与保留清单

按用户确认，现有升级属于项目个人工程贡献。原教程常见能力与这些升级分别描述；本轮未逐行比对课程官方历史版本，不对每行代码作来源鉴定。

| 领域 | 教程基础能力 | 本仓库已有升级，可展开说明的贡献 | 当前处置 |
| --- | --- | --- | --- |
| 业务 | 商户、笔记、关注、签到、领券、订单 | 桌面页面、真实接口联动、体验验证、鉴权与快照等改造 | 保留，不扩展评论等旁支 |
| 缓存 | Redis、Cache Aside、空值与热点重建等经典策略 | 版本栅栏、锁 owner、源 TTL、并发加载隔离、事务 Outbox 失效、Canal CDC | 保留，Phase 2 聚焦正确性 |
| 秒杀准入 | Lua、一人一单、预扣库存、Stream | 类型预检、Redis 服务端活动时钟、活动发布基线、持久预约、异常消息隔离及恢复门禁 | 保留，核对成功与失败边界 |
| 消息与履约 | Stream 异步消费 | Stream→持久 Outbox→RocketMQ 交接、租约投递、Inbox、条件写入、失败复核、重投 | 核心主线，优先验证 |
| 补偿与对账 | 经典补偿思想 | 按预约/订单号释放资格、重复补偿保护、库存差异报告、人工恢复 | 保留；自动补偿闭环待补 |
| 订单生命周期 | 订单与基本状态 | 普通券截止时间、延迟事件、定时兜底、支付/关单竞争保护、稳定下单槽位 | 保留；不能当完整秒杀取消链路 |
| Kafka/社区/媒体 | Feed、点赞、上传等业务 | 行为聚合、持久关系、可靠投影及媒体生命周期 | 冻结功能扩展，不删除已有成果 |
| AI | 非教程核心 | 只读工具、BM25 知识来源与版本、DeepSeek 问答和基础页面 | 保留为 P1，暂缓 SSE/Agent 扩展 |

已有 Caffeine/Memcached 等缓存层不因新优先级被删除，也不再增加新层。盘点报告不建议立即简化或重写缓存；先证明现有边界，再决定是否减少组件。

## 2. 当前真实主链

### 缓存

写入店铺的事务同时登记 `SHOP_INVALIDATE`；提交后立即尝试失效。失败时事件仍可经 Relay/投影重试。Redis 失效脚本递增版本、删除值并发布通知；加载回填同时检查先前版本和锁 owner，旧加载可返回给它自己的请求，但不能写入新缓存。这是最终一致设计，不是所有并发读都必然读到最新值。

证据：[ShopServiceImpl.update](../src/main/java/com/hmdp/service/impl/ShopServiceImpl.java)、[BusinessOutboxServiceImpl](../src/main/java/com/hmdp/service/impl/BusinessOutboxServiceImpl.java)、[CacheClient](../src/main/java/com/hmdp/utils/CacheClient.java)、[回填 Lua](../src/main/resources/cache-fill.lua)、[失效 Lua](../src/main/resources/cache-invalidate.lua)。

### 秒杀与可靠履约

```text
请求
  → Redis Lua：校验类型 / 活动窗口 / 重复资格 / 库存
  → Redis 预扣 + 资格绑定 + Stream 消息
  → Stream Consumer 登记并校验 MySQL 持久预约
  → 开启 MQ 路径时：事务登记 Outbox 履约事件
  → 事务提交后 ACK Stream（责任已转交，不代表订单成功）
  → Relay 租约发送 RocketMQ
  → Consumer：锁事件、校验身份、检查 Inbox
  → 数据库事务：条件扣库存 + 保存订单 + 预约 SUCCESS + Inbox/任务完成
  → Broker 确认
```

关闭 MQ 秒杀开关时仍可由 Stream 消费直接履约。不能把两种路径混写成请求入口直接发送 MQ。

数据库最终库存使用 `stock > 0` 的条件更新；事务失败回滚订单与扣减。幂等由持久预约约束、订单身份核验、锁、Inbox 和事务共同提供，不是 Redis Lua 一层完成。

特别注意：[V1](../src/main/resources/db/migration/V1__reliable_voucher_business.sql) 的秒杀资格唯一约束在 `tb_seckill_reservation(voucher_id, active_user)`，不是直接在所有订单上无条件添加 `(user_id,voucher_id)` 唯一约束。普通券取消后可重领，另有稳定槽位加行锁。不能在简历中写成未经核对的订单表唯一索引实现。

证据：[seckill.lua](../src/main/resources/seckill.lua)、[SeckillServiceImpl](../src/main/java/com/hmdp/service/impl/SeckillServiceImpl.java)、[VoucherOrderServiceImpl.persistSeckill](../src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java)、[BusinessEventServiceImpl](../src/main/java/com/hmdp/service/impl/BusinessEventServiceImpl.java)、[OutboxRelayServiceImpl](../src/main/java/com/hmdp/service/impl/OutboxRelayServiceImpl.java)、[RocketMqTransport](../src/main/java/com/hmdp/messaging/RocketMqTransport.java)。

## 3. 实现、测试与边界对应

| 场景 | 已找到的实现/用例 | 不能据此宣称 |
| --- | --- | --- |
| 旧值回填、并发重建 | CacheLayersIntegrationTest、ReliabilityIntegrationTest 的旧加载/世代隔离用例 | 全部读写线性一致、任意故障时立即刷新 |
| CDC 确认与重放 | CanalBatchTest：投影/检查点失败不 ACK；CacheCdcIntegrationTest：外部更新与重连 | 任意 binlog 丢失都能自动补齐 |
| 最后一份库存、多消费者 | ReliabilityIntegrationTest：concurrentLastStockAndDuplicateDeliveryRemainConsistent、twoLiveConsumersFulfilEachReservationOnce | 已完成大规模交易容量测试 |
| 责任交接 | RocketMqIntegrationTest：luaStreamHandoffAndRocketFulfillmentRemainIdempotent；V7 业务幂等键 | ACK Stream 时订单已经落库 |
| 投递结果不确定 | MessagingIntegrationTest：unknownSendResultRetriesWithSameEventId、consumerCanCompleteBeforePublisherMarksSent | 所有发送故障都已用真实网络断连复现 |
| 租约、重复消息、失败复核 | MessagingIntegrationTest：过期租约、并发重复、人工复核重试 | 端到端 exactly-once，或永不需要人工处理 |
| 消费者接管 | ReliabilityIntegrationTest：restartClaimsAnotherConsumersPendingMessage | 已实测杀进程、断电与磁盘丢失的全部恢复情形 |
| Redis 丢失库存 | 缺失执行库存拒绝准入；活动发布标记与恢复门禁测试 | Redis 数据可全部由 MySQL 无损重建 |
| 补偿 | FAILED 提交后按订单号执行幂等 Lua，重复操作不重复加库存 | Redis 补偿失败后自动重试已经闭环 |
| 对账 | queryInventoryReconciliationReport 比较初始库存、DB 库存、订单、预约和 Redis | 发现差异即自动安全覆盖库存 |
| 普通券关单 | OrderLifecycleServiceImpl、MessagingIntegrationTest、RocketMqIntegrationTest 的定时/延迟/竞争用例 | 秒杀未支付订单的取消和双库存回补已经完成 |

## 4. P0/P1 验证缺口

### P0-A：缓存失败恢复证据需要串成一条链

现有局部并发测试可复用。补充并明确验证：业务更新与 Outbox 同事务提交 → 提交后删除失败 → 后台失效仍失败时事件保留 → Redis 恢复后重投成功 → 新请求读到当前值 → 旧加载不能回填。记录数据库事实、事件状态、缓存版本与观测时间，声明可接受的旧读窗口和超时策略。

Memcached 不可用有有限时降级测试，不等于 Redis 故障也会无条件穿透 MySQL。需要逐路径写明哪些拒绝、哪些读可降级，避免故障时击穿数据库。

### P0-B：失败补偿缺少独立、可持续的自动执行记录

当前 `markReservationFailedAndCompensateRedis` 先提交 FAILED，再补偿 Redis；失败后支持再次调用。代码尚未形成专门的补偿任务状态、租约/重试和完成确认闭环。应在已有 Outbox 体系中设计持久补偿任务，维持按订单号的幂等保护，并覆盖 DB 提交后、Redis 成功前后进程退出的窗口。不能简单扫描 FAILED 就假定全部需要无条件加库存。

### P0-C：Redis 准入和持久交接前的数据损失边界

Redis Lua 成功与 MySQL 持久预约之间仍存在时段，Redis 持久化/恢复策略需要单独说明。Lua 原子执行不等于 Redis 与数据库跨系统事务，也不保证断电后全部请求保存。现有恢复选择拒绝自动重开，避免错误超卖；需要把演练、人工处理步骤和可接受数据损失条件写清。

### P0-D：交易基线与真实进程故障实验尚未成套

缓存报告 `GET /shop/1` 的对照只测读路径，不是交易版本 V1～V4 对照。需要隔离环境、相同数据和同一正确性口径，统一记录请求受理量、真实订单完成量、恢复耗时、重复/超卖、P50/P95/P99、错误率、DB 与消息积压。异步受理 QPS 与履约吞吐分别记录，拒绝请求不混作系统错误。

当前测试中既有真实 Broker 集成，也有通过 stub/异常模拟的发送失败。后续为 Consumer 进程退出、Redis 超时、MQ 故障、数据库回滚、重复消息、缓存失效失败分别建立可重放实验；不把方法调用模拟写成 kill 进程实测。

### P1（可前移）：秒杀订单支付/取消的库存状态机

普通券关单已有；秒杀订单落库后的未支付过期、支付竞争、DB 库存与 Redis 库存释放未形成对应完整链路。要先定义订单与预约各自状态、释放库存是否允许再次领取、活动结束后的释放规则，随后实现条件迁移与持久释放事件。普通券未建执行库存，不能照搬秒杀的库存回补逻辑。

## 5. 历史证据的使用口径

- [backend-audit.json](backend-audit.json) 是 2026-10-10 的阶段审计，含当时 Checkstyle 失败，不应当成当前构建仍失败的结论。
- [java-readability-verification.json](java-readability-verification.json) 是后续历史回归，记录 91 项唯一用例及零风格问题；重复运行的用例不可相加。
- [cache-benchmark.json](cache-benchmark.json) 为 2026-10-11 单实例热点查询对照，约 4922→9332 QPS、P99 6.583→4.151 ms，不代表交易容量。
- 本轮 2026-11-02 盘点没有重跑上述测试，也没有生成新性能指标。现有 AI 测试和页面验证保留，不能替代交易正确性验收。

## 6. 下一阶段的最小交付

先做 Phase 2 的缓存一致性验证，不新增中间件、不重写多层缓存：

1. 为“提交成功但立即失效失败”的 Outbox 恢复建立定向隔离集成测试，串起状态与缓存断言。
2. 复跑已有旧加载栅栏、并发重建、owner、CDC ACK 测试，仅针对失败或缺口修改实现。
3. 生成可复现报告，写明配置、对象 ID、事件 ID、时序、恢复结果和一致性限制。

Phase 1 的盘点到此结束。下一次进入 Phase 2 后才编写或修改业务测试/代码。本轮未删除任何已有升级，未继续 SSE，未更改真实订单、库存或模型配置。

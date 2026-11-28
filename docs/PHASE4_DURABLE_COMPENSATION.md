# Phase 4：失败补偿的持久重试

后续进展（2026-11-05）：新付费秒杀订单的超时关闭与双库存归还已完成，见 [Phase 5](PHASE5_ORDER_EXPIRY.md)。下文保留当时的阶段边界；历史无截止时间订单不自动取消。

2026-11-05：已实现指定失败预约的持久补偿任务。选定回归 29 项通过，真实 RocketMQ 测试 4 项通过，合计 33 项唯一用例，无失败、错误或跳过。本机另跑的 4 项单元回归与上述重复，不重复计数。

## 本次改变

以前人工确认预约失败时，先保存 FAILED，再调用 Redis Lua。Redis 调用失败或提交后进程退出，会留下已失败但尚未完成释放的预约，缺少独立重试任务。

现在在同一 MySQL 事务中保存 FAILED 并登记 `SECKILL_COMPENSATE` Outbox 事件，业务幂等键为 `seckill-compensate:<orderId>`。复用 V7 已有唯一索引，不新增表或中间件。重复确认沿用同一个事件，不重置已完成或待复核任务。

提交后保留一次即时补偿，减少正常路径的等待。即时调用失败会返回“失败状态和补偿任务已保存”，后台仍有任务可执行；不能把该返回值解释成业务事务回滚。若方法参与尚未提交的外层事务，不提前执行 Redis，由持久事件接管，避免外层回滚后库存已释放。

补偿事件复用现有 Relay 租约、退避、Inbox 和人工复核机制。MQ 关闭时走本地消费；MQ 开启时经 RocketMQ 的 `activities` 消费组处理，订阅已加入新类型。开启既有调度时后台会领取任务；集成测试关闭调度、手动推进，未测恢复 SLA。

## 如何避免重复释放

即时执行与事件重放都调用 `SeckillCompensationService`：锁定预约，确认状态为 FAILED、没有对应正式订单，再执行已有 `seckill-compensate.lua`。Lua 只释放用户映射仍指向原订单号的资格。

Redis 效果不参与 MySQL 事务。如果 Lua 成功后 Inbox 写入失败，数据库任务仍未确认；下一次重放由 Lua 的归属校验避免重复加库存，再写入 Inbox 和 DONE。旧补偿到达时若用户已有新预约，不释放新预约。

FAILED 表示已经确认业务失败，不表示 Redis 一定完成释放。补偿任务的 PENDING、SENT、REVIEW、DONE 是另一条执行状态；需结合任务、Redis 和对账结果判断。

## 已验证

| 场景 | 结果 |
| --- | --- |
| Redis 补偿条件失败 | FAILED 与同一个持久事件保留；恢复后重试完成，Inbox 为 1 |
| Outbox 插入失败 | 用真实 MySQL 触发器拒绝插入，FAILED 迁移一同回滚，Redis 无释放 |
| Redis 成功、Inbox 写入失败 | 触发器使数据库确认事务失败；Redis 已回补，重放不重复加库存 |
| 旧任务晚于新预约到达 | 保留新预约的订单归属、预占和 PROCESSING 状态 |
| 正式订单已存在 | 人工确认和延迟执行均拒绝回补，异常任务保留待处理 |
| 外层事务回滚 | FAILED、事件同时回滚，不执行 Redis 释放 |
| 重试耗尽及人工恢复 | 本地路径 10 次失败进入 REVIEW，人工重投仍使用原事件号 |
| 并发重复投递 | 8 次投递只释放一次，只有一个 Inbox |
| 真实 Broker | 补偿新类型、重复消息、原有秒杀履约交接和普通订单延迟关单共 4 项通过 |

第一轮 29 项由新增补偿集成 8 项、MessagingIntegrationTest 14 项、既有秒杀集成 3 项、相关单元测试 4 项组成。真实 Broker 的 4 项是独立测试类，不与前 29 项重复。

## 证据与复验

- [汇总验证记录](phase4-compensation-verification.json)
- [8 个补偿场景的事件和库存轨迹](phase4-compensation-evidence.json)
- 选定回归日志：`target/phase4-compensation-integration.log`
- Broker JUnit：`target/surefire-reports/TEST-com.hmdp.messaging.RocketMqIntegrationTest.xml`
- Broker 父脚本会话被中断，其日志未完整收尾；已核对之后生成的 JUnit 报告，4 项均通过。测试容器已退出，残留依赖另行按专用项目清理。汇总记录区分 JUnit 结果与父脚本退出状态。

```powershell
.\scripts\verify-seckill-compensation.ps1
.\scripts\verify-seckill-compensation-broker.ps1
```

脚本分别使用 `citymate-compensation-p4` 和 `citymate-compensation-mq-p4`，数据库必须为 `hmdp_optimization_test`。默认清理专用测试环境，保留外部 Maven 缓存，不操作 `citymate` 业务环境。需保留失败现场时使用 `-KeepEnvironment`。

## 仍然保留的边界

- Redis 失败用异常库存内容触发真实 Lua 拒绝，不是实际 Redis 服务宕机。数据库失败使用测试触发器，不是断网或实际杀进程。
- 本地与 Broker 消费失败的重试计数和上限沿用原机制，不能把本地 10 次发送失败推导成所有 Broker 故障统一 10 次。超过自动处理边界仍需复核。
- 即时补偿成功后事件可能仍为 PENDING，待后台幂等重放并持久确认；重复人工确认不自动解封 REVIEW 任务，使用已有任务复核入口重投。
- 历史 FAILED 预约不会被批量扫描后盲目加库存。没有补偿事件的历史记录，需要核对后重新调用确认入口登记任务。
- 仍依赖 Redis 预占数据的完整性。现有 Lua 的无操作返回不证明丢失的映射或库存已经重建；Redis 数据丢失需继续按恢复门禁和对账处理。
- 本次只释放未履约失败预约的 Redis 预占；不增加 MySQL 库存，因为此前订单事务没有成功扣减或已回滚。秒杀订单落库后的未支付取消及 DB/Redis 双库存释放是下一阶段。
- 未新增压测指标，未宣称任意故障下 exactly-once、无损恢复或生产高可用。

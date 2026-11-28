# Phase 3：秒杀资格与库存正确性验证

验证日期：2026-11-05。选定的 15 项测试全部通过，0 失败、0 错误、0 跳过；其中 11 项集成测试、4 项单元测试。Checkstyle 与 Java 8 目标编译通过。生产 Java、Lua 和数据库迁移未修改，没有新增性能指标。

## 验证范围

运行脚本：[verify-seckill-correctness.ps1](../scripts/verify-seckill-correctness.ps1)。独立 Compose 项目为 `citymate-seckill-p3`，数据库必须为 `hmdp_optimization_test`。只启动 MySQL、Redis 和 Memcached，关闭后台调度、MQ 和 CDC，手动调用真实 Stream 消费处理器。

| 场景 | 本轮断言与结果 |
| --- | --- |
| 40 次不同用户请求竞争最后一份库存 | 只有一个受理；重复投递后只有一个订单，DB 与 Redis 库存均为 0 |
| **新增：同一用户 32 次请求，12 个工作线程** | 全部返回同一个订单号，Stream 仅一条消息；履约前 DB 库存仍为 1、Redis 已预占为 0、订单为 0；履约及重复投递后订单为 1、DB/Redis 库存为 0、预约 SUCCESS |
| **新增：数据库订单 INSERT 失败** | 测试触发器在真实 INSERT 时抛出 SQLSTATE 45000；同事务库存扣减回滚，DB 库存为 1、订单为 0；预约 PROCESSING、attempts 为 1、Redis 预占保留、pending 为 1；移除触发器后同消息重试，最终订单为 1、DB/Redis 库存为 0、pending 为 0；再投递不重复扣减 |
| 履约库存不足与补偿幂等 | 多次失败进入 REVIEW；指定预约补偿两次不重复加 Redis 库存，旧消息无法复活 FAILED 预约 |
| Redis 执行库存缺失 | 入口拒绝；人工恢复后已成功用户仍对应原订单，其余用户不能获得不存在的库存 |
| Stream 类型异常 | 类型预检失败不扣库存、不占资格 |
| 未持久交接前 Redis 数据丢失 | 模拟删除相关数据后调用初始化，已发布活动不自动重开；这是失败关闭策略，不证明在途请求可以无损恢复 |
| 两个消费者并行处理 10 份库存 | 最终订单和 SUCCESS 预约均为 10，DB 库存为 0 |
| 冲突消息 | 错误消息隔离，不污染真实预约与请求状态 |
| DB 提交后状态缓存写入失败 | 已成功预约不降级；消息保留后可修复展示状态并 ACK |
| pending 消息接管 | 通过真实 Redis 消费者组认领另一消费者的 pending 消息并完成处理；不是实际终止进程实验 |

相关单元测试为 `SeckillMessageTest` 1 项、`VoucherOrderReliabilityTest` 3 项。单元测试中的故障为 mock，不能当作真实数据库或网络故障。

## 证据与复验

- [汇总结果与选定用例](phase3-seckill-verification.json)
- [同一用户并发的库存与消息轨迹](phase3-same-user-evidence.json)
- [数据库回滚与重试轨迹](phase3-database-rollback-evidence.json)
- 本地原始日志：`target/phase3-seckill-integration.log`
- 本地 JUnit：`target/surefire-reports/TEST-com.hmdp.reliability.ReliabilityIntegrationTest.xml` 及对应两个单元测试报告。下次测试可能覆盖它们，汇总 JSON 保存本轮用例和结果。

```powershell
.\scripts\verify-seckill-correctness.ps1
```

默认退出时只清理该测试项目和临时卷；外部 Maven 缓存保留，业务项目 `citymate` 不受影响。需要保留失败现场时使用 `-KeepEnvironment`。测试触发器只在隔离数据库内建立，并在 finally 中删除；若测试进程被强行终止，应先清理专用测试环境再复验。

## 实现的边界

- 同一用户的 32 次成功响应是一次受理的幂等查询结果，不能写成 32 个新订单；线程数和请求数仅描述测试规模，不是 QPS 或容量结论。
- Redis 预占和 MySQL 最终扣库存分属不同阶段，在途时两边库存允许不同。返回订单号不表示落库；预约 SUCCESS 表示已履约，不证明真实支付完成。
- 本轮验证的是 Stream 直接履约路径。没有启动 RocketMQ、Kafka、Canal；没有注入网络断连、断电或实际杀进程，也不证明端到端 exactly-once。
- MySQL 触发器证明库存 UPDATE 与订单 INSERT 处于可回滚事务；它不是 MySQL 服务宕机或连接中断实验。恢复后的消息由测试手动重试，不是恢复 SLA。
- 持久资格唯一约束位于预约表 `(voucher_id, active_user)`，不是整个订单表无条件 `(user_id, voucher_id)` 唯一。
- Redis 准入到 MySQL 持久交接前仍有 Redis 持久化与数据损失边界；不能宣称库存和资格可全部由 MySQL 无损重建。
- FAILED 状态提交后的 Redis 补偿仍缺少独立持久重试任务；秒杀未支付订单的超时取消与双库存释放仍未完成。本轮没有以测试通过掩盖这些缺口。

## 下一步

Phase 4 聚焦持久责任交接、消费幂等和补偿恢复。优先在已有 Outbox 机制中补齐可持续的补偿任务、重试与确认，并验证 DB 提交后 Redis 补偿失败的窗口。随后单独处理秒杀订单取消与库存释放状态机。继续保持单体架构，不新增中间件。

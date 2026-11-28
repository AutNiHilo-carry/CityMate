# Phase 6G：真实应用进程终止、重启与幂等恢复

2026-11-08。最终完整运行 `ce0e441eec1e43c9ae74307d8d9ce291`。对不含测试探针的独立 JAR，在两个外部可控窗口执行 SIGKILL，再启动同一容器与镜像，观察原任务自动恢复。本轮没有改 Java/Lua，没有添加生产故障开关。

## 本次通过的两项

每项只受理一个合成用户的免费券请求，初始库存 2；它是恢复正确性演练，不是压测。表内恢复时间从 docker start 前开始观察，包含启动、消费者接管/重连和外部轮询，不是 SLA。

| 故障窗口 | 退出码 | 停机后、重启前的持久状态 | 本次恢复观察 | 最终订单 / 双库存剩余 |
| --- | --- | --- | --- | --- |
| Stream 未交接给 Outbox | 137 | 预约 1 / Outbox 0 / 订单 0 | 30.95 秒 | 1 单 / 余 1 |
| Outbox 已 SENT，订单未落库 | 137 | 预约 1 / Outbox SENT / 订单 0 | 48.44 秒 | 1 单 / 余 1 |

两项最终均保留原订单号，预约 SUCCESS、正式订单、fulfillment Inbox 各 1，持久履约事件 DONE，Stream pending 为 0。每个活动的数据库/Redis 库存均为 1，满足初始 2 = 已履约 1 + 剩余 1。

## 如何把进程停在指定位置

仅在 citymate-crash-p6 的 hmdp_optimization_test 数据库使用外部会话的临时 WRITE 表锁。先锁表，再通过会话级命名锁发布“已就绪”标记；创建者会话 ID 已记录。锁只允许预约表或订单表，业务库地址不可配置进该脚本。

请求通过真实 HTTP 返回受理结果后，采集 MySQL PROCESSLIST 中包含原订单号的等待 SQL，以及 Stream PEL/持久事件；条件满足才执行 `docker kill --signal=KILL citymate-crash-p6-app`。验证退出码 137、非 OOM、进程已停止，然后释放外部阻塞会话，读取数据库事实，再执行 docker start。

释放闸门时只终止本脚本自己创建的 MySQL 阻塞会话，不杀应用数据库会话，不写订单、预约状态、库存或 Inbox。应用容器 ID/镜像 ID 保持不变、StartedAt 改变，运行命令始终为 Java 8 的 `java -Xmx512m -jar /app/app.jar`，无宿主机 bind mount、无 SpringBootTest JVM。

## 场景一：Stream 的未完成交接

原消息已经进入消费者组 PEL；包含原订单号的预约 INSERT 被表锁挡住。终止 JVM 后没有 `SECKILL_FULFILL` 持久任务或正式订单，Redis 仍保存原消息和预占资格。

值得注意：本次释放外部锁后，重启前读到预约 1 条。**JVM 死亡不保证已发往数据库的单条语句一定没提交。**预约登记与 Outbox 有不同事务步骤，因此恢复不能依赖“所有步骤都回滚”的假设。

重启后未改约 30 秒的 pending 接管配置，不手工 XCLAIM、不补插 Outbox。新消费者处理原消息，按原订单号幂等登记并建立持久交接，最终完成同一订单。本次观察约 30.95 秒；这验证了 ACK 必须晚于持久交接的职责边界，不能推广为 Redis 自身宕机也不丢数据。

## 场景二：持久事件已发送，正式履约未完成

Stream 已 ACK，原预约和 Outbox 事件已提交；事件 3 为 SENT。包含原订单号的 `SELECT ... FROM tb_voucher_order WHERE id=...` 被订单表锁挡住，随后杀进程。

停机后原订单与 fulfillment Inbox 仍为 0，数据库库存仍为 2。重启后原持久事件完成消费，eventId 不变、状态 DONE，正式订单和 Inbox 各 1，数据库库存只扣一次。本次观察约 48.44 秒。没有重置任务状态、重新创建交接事件或逐条代替消费者执行。

边界：该故障点挡在订单查询处，尚未进入核心库存更新/订单插入；**没有验证“扣库存之后、插单之前”发生进程终止时的事务回滚**。本次事件实际为 SENT，也不能据此声称已验过 SENDING 租约到期接管或数据库提交后、Broker ACK 前的精确崩溃窗口。

## 恢复之后再验证重复

每个场景先完成原订单的恢复对账，再将原 Stream 消息的同一组 id/userId/voucherId 写入两个新消息记录。这四条重放是幂等验证，不参与补救原请求。

脚本等待消费者组 last-delivered-id 越过最新重放 ID，并确认 pending 为 0，避免把“消息尚未被读到”当成去重成功。随后同一用户再次 HTTP 领券，必须返回原订单号；本人状态查询必须为 SUCCESS。两场景的订单/Inbox 都仍为 1，双库存都仍为 1，没有重复扣减或重复下单。

这覆盖源消息与用户重复提交，不代表用脚本精确注入过所有 Broker 重复消费窗口。

## 执行记录与范围

- 最终完整运行：两个故障点、两次 SIGKILL/同容器重启，均通过；没有改生产代码和持久化策略，AOF always 保留。
- 首轮只完成场景一，在场景二等待 SQL 出现时遇到 SQL NULL 解析错误，尚未执行第二次 SIGKILL。该部分记录单独保留，未并入最终两项通过结论。
- 修复后新增三项 Python 诊断回归：无等待者返回空列表、SQL 与原订单关联、禁止对未许可表设置闸门，全部通过。Java 回归不在本轮重复执行；本轮 Java 8 打包与 Checkstyle 通过。
- 两轮专用容器/卷和唯一运行镜像均已清理，旧压测主报告未覆盖，业务环境未更新，未 push。
- 本轮 MySQL、Redis、Broker 都保持运行，已受理 HTTP 回复也已送达；尚未覆盖基础设施崩溃、断电、网络分区、丢失 HTTP 回复和所有业务故障组合。单次恢复观察值不能写成生产可用性或恢复时限承诺。

## 复验与证据

```powershell
python scripts/test-process-recovery.py
.\scripts\measure-process-recovery.ps1
```

脚本仅使用专用项目与 18088 端口，拒绝已有同名环境及端口占用。不要把诊断表锁步骤复制到业务数据库。

- [最终验证汇总](phase6-process-recovery-verification.json)
- [完整故障记录](process-recoveries/ce0e441eec1e43c9ae74307d8d9ce291/application.json)
- [Stream 窗口证据](process-recoveries/ce0e441eec1e43c9ae74307d8d9ce291/980701-evidence.json)
- [持久交接窗口证据](process-recoveries/ce0e441eec1e43c9ae74307d8d9ce291/980702-evidence.json)
- [JAR 资源核验](process-recoveries/ce0e441eec1e43c9ae74307d8d9ce291/artifact.json)、[初始运行方式](process-recoveries/ce0e441eec1e43c9ae74307d8d9ce291/runtime.json)
- [首轮部分记录](process-recovery-attempts/9fc0ac86684a40d98680879d0632e722/attempt.json)

下一步可进一步定位数据库核心写入后的事务中断窗口，以及补偿、关单链路的进程中断；不以增加组件替代故障证据。

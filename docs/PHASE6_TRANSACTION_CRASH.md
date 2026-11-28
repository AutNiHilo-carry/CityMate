# Phase 6H：库存已更新、订单未插入时的真实进程中断

2026-11-08。运行 `87b77422140041e3b204e1d0a84f9417`。上一轮的订单表 WRITE 锁挡住了订单查询，本轮改为外部主键范围锁，让业务查询和库存更新先执行，仅阻塞订单 INSERT。业务 Java、Lua、配置隔离级别和持久化策略均未改。

## 结果

| 观察阶段 | 已提交数据库库存 | 外部诊断读到的库存 | 正式订单 | fulfillment Inbox |
| --- | --- | --- | --- | --- |
| 杀进程前，订单 INSERT 等锁 | 2 | 1（未提交） | 0 | 0 |
| SIGKILL 后、重启前，原事务已消失 | 2 | 2 | 0 | 0 |
| 重启后原事件履约完成 | 1 | — | 1 | 1 |
| 额外重放源消息与重复 HTTP 后 | 1 | — | 1 | 1 |

两次最终对账中 Redis 剩余库存均为 1，仍对应最初的预占资格；没有重复下单、重复扣减或新建替代事件。初始库存 2 = 最终订单占用 1 + 剩余库存 1。

## 如何确认真正经过库存更新

应用返回原订单号后，记录到包含该订单号的订单 INSERT 正在 InnoDB LOCK WAIT，事务 ID 为 `2332`，应用连接 ID `20`，实际应用隔离级别 READ COMMITTED，事务统计 rowsModified 为 2。

同时捕获该事务持有的 `tb_seckill_voucher` 主键记录 X 锁，锁数据为合成券 ID 980801；正常已提交视图看到库存 2，而独立诊断会话看到未提交值 1。这些证据共同证明本次已经进入库存更新后的窗口，而不是仍挡在查询之前。rowsModified 是事务统计，不能解读成已提交的库存扣减次数。

## 诊断锁与隔离级别的范围

外部阻塞会话在隔离库中使用 REPEATABLE READ 事务，对订单主键正值范围执行 SELECT FOR UPDATE，以覆盖后续 INSERT 的位置；普通一致性查询仍能进行。这一机制参考 [MySQL InnoDB 锁说明](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html)。

另一个外部会话临时使用 READ UNCOMMITTED，仅用于核验未提交库存；它不是应用的业务读路径。会话级隔离和该级别允许不一致读取的语义见 [MySQL 隔离级别说明](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html)。脚本未执行 SET GLOBAL，未改变业务连接、配置或数据库默认隔离级别。

只允许本地固定的测试库和合成活动，诊断参数校验后才执行 SQL。外部会话只持锁和读取，不修改业务行；命名锁标记用于确认闸门已建立，阻塞会话的连接 ID 单独记录。

## 先证明回滚，再允许重启

在上述证据满足后，对实际 JAR 的 PID 1 执行 SIGKILL，记录退出码 137、非 OOM、进程退出。随后只结束本脚本自己的外部阻塞会话，不杀应用数据库连接，不改订单、库存、预约或 Outbox。

释放锁后等待：原 InnoDB 事务 ID 已消失；诊断库存恢复到 2；已提交库存为 2；订单和 Inbox 都为 0。应用此时仍处于停止状态。因此回滚发生在重新启动和事件重试之前，不能把后续履约成功冒充回滚证据。

Outbox 与预约仍各有一条，Redis 保留原预占、不手工回补或重新扣减。之后启动同一容器和镜像，原事件完成履约，eventId 不变、状态 DONE，原订单/预约/Inbox 各 1，双库存均为 1。本次从 docker start 到观察恢复约 48.33 秒，包含启动、Broker 重连/重投及轮询，不是 SLA。

恢复对账通过之后，另外重放原 Stream 内容两次，并由同一用户重复领券；消费进度越过重放 ID、pending 归零，HTTP 返回原订单号，本人状态为 SUCCESS，订单和双库存保持不变。重放不是原请求的恢复手段。

## 验证与边界

- 独立 Java 8 可执行 JAR、无源码绑定挂载、无测试探针、没有生产故障开关；实际 SIGKILL 和同容器重启一次。
- 新增四项诊断回归：空事务快照、不能把 SELECT 等待当 INSERT 窗口、INSERT 必须匹配原订单、非法 SQL 标记提前拒绝，均通过。本轮打包和 Checkstyle 通过，Java 回归未重复执行。
- 数据库、Redis、Broker 未重启或断电；只有应用进程退出。AOF always 保留，没有手工补单或调整业务事实。
- 本次只覆盖一个请求、一个事务位置；不代表所有并发故障、已提交后 Broker ACK 丢失、多机切换、真实支付或退款都已验证。
- 专用容器/卷和唯一运行镜像已清理，旧主基线未覆盖，业务环境未更新，未 push。

## 复验与证据

```powershell
python scripts/test-transaction-crash.py
.\scripts\measure-transaction-crash.ps1
```

只在 citymate-tx-crash-p6 专用项目与 18089 端口运行，拒绝已有同名环境或端口占用。不要将诊断锁和脏读步骤复制到业务数据库。

- [验证汇总](phase6-transaction-crash-verification.json)
- [完整事务证据](transaction-recoveries/87b77422140041e3b204e1d0a84f9417/980801-evidence.json)
- [本轮运行结果](transaction-recoveries/87b77422140041e3b204e1d0a84f9417/application.json)
- [打包核验](transaction-recoveries/87b77422140041e3b204e1d0a84f9417/artifact.json)、[运行方式](transaction-recoveries/87b77422140041e3b204e1d0a84f9417/runtime.json)
- [上一轮两个进程中断窗口](PHASE6_PROCESS_RECOVERY.md)

至此已有三个选定应用进程中断窗口的真实证据。数据库提交后的确认边界、补偿/关单中断和基础设施故障仍是独立的待验证范围，不能从这三项推导全故障保证。

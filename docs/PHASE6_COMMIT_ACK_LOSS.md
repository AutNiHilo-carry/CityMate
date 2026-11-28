# Phase 6I：订单已提交，消费位点未推进时的重投去重

2026-11-08 隔离实测通过。运行 ID：`5019deecf9c34848a6ca35c64f3a0e18`。正式 Java 8 可执行 JAR，无源码挂载、测试 JVM 或生产故障开关。真实 RocketMQ 5.3.2、AOF always；沿用现有业务逻辑。

## 要回答的问题

数据库已经提交订单和 Inbox，但 Broker 尚未记录消费进度，此时应用退出，消息再次到达会不会重复扣库存？本轮先断开专用 Broker 的消息网络，允许已开始的数据库事务提交，再 SIGKILL 应用；恢复网络并重启同一容器，让 Broker 自动重投。不是只杀进程就能精确命中窗口，也没有手工重发消息或重置 offset。

当前使用经典并发 PushConsumer。监听器成功返回后，客户端更新本地消费进度，Broker 消费位点是另一个边界；不应将它描述成 POP 单消息 ACK。[RocketMQ 并发消费源码](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/impl/consumer/ConsumeMessageConcurrentlyService.java)可核对该路径。本轮使用 mqadmin 的 BrokerOffset、ConsumerOffset 两列核验服务端进度，列含义见[官方 consumerProgress 实现](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/tools/src/main/java/org/apache/rocketmq/tools/command/consumer/ConsumerProgressSubCommand.java)。

## 如何稳定制造窗口

1. 在独立测试库准备库存 2、一个合成用户，调用实际受保护的活动发布与 HTTP 领取接口。
2. 外部测试事务用范围锁阻塞订单 INSERT。确认应用事务已到该 INSERT，Outbox 原事件已 SENT，Broker 原队列有且仅有一条履约消息，消费位点为 0。
3. Broker 仅连接专用 MQ 网络，应用同时连接 MQ 与数据库网络。断开 Broker 的 MQ 网络，TCP 探测从成功变为超时；Broker 进程不重启，数据库连接保留。
4. 释放外部锁，让业务提交。查到订单、成功预约、Inbox 各 1，Outbox DONE、双库存余 1；此时重复消费计数为 0。
5. SIGKILL 应用，确认退出码 137、非 OOM。恢复 Broker 网络，**在应用仍停止时**查询 Broker 实时消费位点仍为 0；不能只依赖磁盘 offset 文件可能滞后的快照。
6. 重启原容器与原镜像。自动重投命中 Inbox 重复分支，并推进 Broker 消费位点。未手工补单、XADD、重新发布履约事件或修改消费进度。

## 本轮结果

| 证据 | 观测结果 |
| --- | --- |
| 原订单号 | `657728847873048577` |
| 原履约事件 | eventId 1，重启前后未更换 |
| 提交后、杀进程前 | 订单 1、Inbox 1、预约成功 1，双库存余 1 |
| 恢复网络、应用仍停止 | 原队列 BrokerOffset 1、ConsumerOffset 0 |
| 重启后的重复分支计数 | `1.0`，来自新 JVM 的 fulfillment/duplicate 指标 |
| 自动重投后的消费进度 | BrokerOffset 1、ConsumerOffset 1、Diff 0 |
| 最终对账 | 原订单 1、Inbox 1、预约成功 1、双库存余 1，未完成履约事件与 Stream Pending 为 0 |
| HTTP 重复领取 | 返回原订单号 |

重启至重投/位点核验的观察时间约 **21.25 秒**，包含应用启动、客户端连接与外部轮询，不是 SLA 或恢复时限承诺。Broker 的容器 ID、PID、启动时间保持一致。去重指标与 Broker 进度共同证明重复分支确实执行，不能仅凭最终一条订单推断消息发生了重投。

## 复验与证据

```powershell
python scripts/test-commit-ack-loss.py
.\scripts\measure-commit-ack-loss.ps1
```

仅使用 `citymate-ack-p6`、端口 18090 和 `hmdp_optimization_test`。脚本拒绝已有同名环境；不要把断网、锁表或诊断读操作复制到业务环境。

- [验证汇总](phase6-commit-ack-verification.json)
- [完整阶段证据](commit-ack-recoveries/5019deecf9c34848a6ca35c64f3a0e18/980901-evidence.json)
- [运行结果](commit-ack-recoveries/5019deecf9c34848a6ca35c64f3a0e18/application.json)
- [JAR 核验](commit-ack-recoveries/5019deecf9c34848a6ca35c64f3a0e18/artifact.json)、[真实运行方式](commit-ack-recoveries/5019deecf9c34848a6ca35c64f3a0e18/runtime.json)
- [此前库存更新后的事务回滚](PHASE6_TRANSACTION_CRASH.md)

5 项 Python 诊断检查通过，Java 8 打包与 Checkstyle 通过；本轮没有重复跑整套 Java 回归。Java/Lua 及 Phase 6E 留存哈希一致，Phase 6B 两份主基线逐字节保留。专用容器、卷和运行镜像已清理，业务环境未更新，未 push。

这是一个请求的受控网络中断加应用终止实验。累计四个选定窗口有真实证据，但不代表消息恰好投递一次，也不代表所有并发、补偿/关单中断、Redis/MySQL/Broker 崩溃、多机切换或真实支付均已验证。

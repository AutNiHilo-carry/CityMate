# Phase 6J：关单已提交、Redis 库存尚未归还时的进程恢复

验证日期：2026-11-08。运行 ID：`bba0ac72db424a3c88ce50612bc1e3da`。本轮真实隔离实验通过，生产业务代码和 Lua 未改。

## 场景与责任

付费秒杀履约后，订单 UNPAID、数据库与 Redis 库存均从 2 变为 1。超时关单事务将订单改为 CANCELLED、数据库库存归还至 2，并登记唯一 SECKILL_RELEASE 任务及 ORDER_CLOSED 事件。Redis 回库由后续释放任务负责，不能因为数据库已回库便认为两边立即一致。

本轮暂停专用 Broker 通道，等待订单自然到期与数据库定时扫描完成关单；确认释放任务已持久化、Redis 尚未释放后，SIGKILL Java。恢复消息网络并重启原容器，核验原任务自动续跑。没有改数据库订单状态、手工调用关单方法或补发释放消息。

## 实际步骤与结果

| 阶段 | 订单状态 | 数据库库存 | Redis 库存 | 释放任务 / Inbox |
| --- | --- | --- | --- | --- |
| 付费券正常落库 | UNPAID | 1 | 1 | 0 / 0 |
| 暂停 Broker，定时扫描关单提交 | CANCELLED | 2 | 1 | 已登记 1 / 0 |
| SIGKILL 后、应用仍停止 | CANCELLED | 2 | 1 | 已登记 1 / 0 |
| 原 JAR 重启并自动恢复 | CANCELLED | 2 | 2 | DONE 1 / 1 |

- 原订单号：`657746745001771009`；前后仍是一单，预约 SUCCESS 表示曾完成履约，不表示订单仍有效或已付款。
- 原释放事件：eventId **4**，恢复前后未更换；ORDER_CLOSED 也仅一条。
- Java 退出码 137、非 OOM；重启前后容器与镜像一致。Broker 仅断开/恢复消息网络，进程 PID 与启动时间未变；MySQL、Redis 保持运行。
- 恢复后存在按原订单写入的释放标记，TTL **-1**，一人一单资格仍指向原订单。相同用户重复领取返回原订单号，不再次领取。
- 重复 HTTP 后再观察 12 秒，数据库与 Redis 库存仍为 2，释放任务及其 Inbox 各一条，无新增订单或库存增加。没有在本轮故意重放释放消息，不能把这一步表述成释放消息重投测试。
- 重启至外部观察恢复完成约 **34.875 秒**，包含启动、消息重连/重试和轮询，不是 SLA。

## 配置、验证与证据

仅在专用运行配置将 `citylens.reliability.order-timeout-minutes` 设为 1；实际订单创建到截止相差 60 秒，生产默认 30 分钟未改。未使用真实支付，也未模拟付款；这是有价格的待支付券。

真实 Java 8 `java -jar`，无源码挂载和测试 JVM；JAR 的 9 份 Lua、类版本和正式资源核验通过。Java 8 打包与 Checkstyle 通过，5 项 Python 诊断检查通过；本轮没有重复执行整套 Java 回归。

```powershell
python scripts/test-expiry-crash.py
.\scripts\measure-expiry-crash.ps1
```

专用 Compose 项目 `citymate-expiry-p6`，HTTP 端口 18091，数据库 `hmdp_optimization_test`。拒绝已有同名环境与端口占用。脚本和故障步骤只用于隔离测试。

- [验证汇总](phase6-expiry-crash-verification.json)
- [分阶段原始证据](expiry-recoveries/bba0ac72db424a3c88ce50612bc1e3da/981001-evidence.json)
- [完整运行结果](expiry-recoveries/bba0ac72db424a3c88ce50612bc1e3da/application.json)
- [JAR 核验](expiry-recoveries/bba0ac72db424a3c88ce50612bc1e3da/artifact.json)、[实际运行方式](expiry-recoveries/bba0ac72db424a3c88ce50612bc1e3da/runtime.json)
- [已有超时关单实现与回归](PHASE5_ORDER_EXPIRY.md)

Java/Lua 与既有留存哈希一致，Phase 6B 两份主基线逐字节保留。专用容器、卷与运行镜像已清理，业务环境未更新，未 push。

## 结论边界

本轮证明所选窗口内，数据库已提交的关单与持久释放任务能在应用中断后继续完成，最终双库存对齐。实际关单触发来自定时扫描，不能说断网期间已验证 Broker 延迟消息到达。

累计五个选定窗口已有真实证据。仍未覆盖 Redis 释放 Lua 执行后、Inbox 提交前的中断，未完成履约的失败补偿进程中断，以及基础设施崩溃、多机切换、最大容量、真实支付退款等。一个订单与短时间观察不代表所有并发时序均安全。

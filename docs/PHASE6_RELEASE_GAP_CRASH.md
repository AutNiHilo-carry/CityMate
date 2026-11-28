# Phase 6K：Redis 已释放、Inbox 尚未提交时的崩溃与重试

2026-11-09 隔离实测通过。运行 ID：`bb22aef5476248dabe454f610d7a1d57`。本轮补充验证两个存储之间的执行边界，未修改生产 Java、Lua、隔离级别或持久化配置。

## 为什么还需这个窗口

上一轮验证数据库已关单、Redis 尚未回库时，持久任务能继续执行。这次让 Redis 真正执行回库，但阻塞同一次消费事务后续的 Inbox INSERT，再杀掉应用：MySQL 未提交的消费事务回滚，Redis 的已执行效果仍存在。消息重试必须识别释放标记，避免再加一次库存。

当前 seckill-release.lua 会在同一次脚本中增加库存、写入以原订单为值的永久释放标记；重试发现相同标记时返回 0，继续完成数据库 Inbox 与 Outbox 提交。本轮未直接记录 Lua 重试返回值，结论来自已核对的调用路径、重启前后的持久状态和库存对账。

## 故障制造与证据

1. 实际 HTTP 创建付费秒杀订单，双库存从 2 变为 1。仅测试配置将超时设为 1 分钟，真实截止相差 60 秒。
2. 暂停专用 Broker 网络，等待自然到期，由定时扫描完成关单：数据库库存 2、Redis 1，唯一释放事件已登记。
3. 外部诊断事务以 REPEATABLE READ 对缺失的 `(activities, 原释放 eventId)` Inbox 主键加锁。业务会话仍使用原有隔离级别，不修改全局设置；普通一致性读取可以继续。
4. 恢复 Broker 网络。捕获原释放事件对应 Inbox INSERT 的 LOCK WAIT，同时 Redis 已为 2、永久释放标记已存在，但已提交视图 Inbox 为 0、任务未 DONE。
5. SIGKILL Java，退出码 137、非 OOM；释放外部测试锁。在应用仍停止时核对原数据库事务已消失、Inbox 仍为 0，Redis 库存与标记保留。
6. 重启同一容器、同一镜像，自动重试原释放事件。最终仅一条释放 Inbox，任务 DONE，数据库和 Redis 都为 2。

| 时点 | 数据库库存 | Redis 库存 | 释放标记 | 已提交释放 Inbox |
| --- | --- | --- | --- | --- |
| 关单提交、消息通道暂停 | 2 | 1 | 无 | 0 |
| Redis 脚本已执行、Inbox INSERT 受阻 | 2 | 2 | 原订单，无 TTL | 0 |
| 杀进程且原事务消失，尚未重启 | 2 | 2 | 保留 | 0 |
| 自动重试并完成 | 2 | 2 | 保留 | 1 |

原订单：`658031544283168769`。原释放事件：**4**。受阻事务 ID：`2569`，实际隔离级别 `READ COMMITTED`。未手工补单、重发消息、重置任务状态或消费位点。

恢复后再次 HTTP 领取返回原订单号；再观察 12 秒，订单仍 CANCELLED，原订单/预约/履约 Inbox 各一条，释放事件/释放 Inbox/关单事件也各一条，双库存均为 2，一人一单资格和无 TTL 释放标记保留。

重启到观察完成约 **49.641 秒**，包含应用启动、重连/重试、分布式锁到期等待（若发生）和外部轮询，未分别量化各段，不是 SLA。Broker 仅切换网络，PID 与启动时间一致；Redis/MySQL 没有崩溃。

## 复验与归档

```powershell
python scripts/test-release-gap-crash.py
.\scripts\measure-release-gap-crash.ps1
```

仅操作 `citymate-release-gap-p6` 专用环境，端口 18092，数据库 hmdp_optimization_test。不要把诊断锁与断网步骤复制到业务环境。

- [验证汇总](phase6-release-gap-verification.json)
- [原始阶段证据](release-gap-recoveries/bb22aef5476248dabe454f610d7a1d57/981101-evidence.json)
- [运行结果](release-gap-recoveries/bb22aef5476248dabe454f610d7a1d57/application.json)
- [正式 JAR 核验](release-gap-recoveries/bb22aef5476248dabe454f610d7a1d57/artifact.json)、[实际运行方式](release-gap-recoveries/bb22aef5476248dabe454f610d7a1d57/runtime.json)
- [上一轮关单已提交、Redis 待释放](PHASE6_EXPIRY_CRASH.md)

8 项 Python 诊断检查通过，Java 8 打包与 Checkstyle 通过；本轮没有重复运行整套 Java 回归。正式运行无源码挂载、测试 JVM 或生产故障开关。生产文件与既有留存哈希一致，Phase 6B 两份主基线逐字节保留。专用环境与运行镜像已清理，业务环境未更新，未 push。

累计六个选定故障窗口有证据，仍不代表全故障覆盖。本轮仅一个订单，Redis 保持运行；未验证 Redis 已回库但持久化丢失、基础设施崩溃、多节点切换、未履约失败补偿的进程中断或真实支付退款。

# Phase 6E：不可变 Lua 初始化加载与优化对照

2026-11-07。本轮完成生产代码中的脚本加载改造、22 项选定回归，以及新跑的优化前/后两轮隔离对照。没有修改 Lua 正文或降低 Redis 持久化要求。

## 修改与行为边界

RedisScripts.number 现在在实例初始化时通过 ClassPathResource 的 InputStream 一次读取 UTF-8 正文，拒绝缺失/空白资源，用内存文本构建 DefaultRedisScript<Long> 并预先计算 SHA。核心 Bean 的静态脚本在启动阶段初始化，首次使用的普通类在类初始化时加载。

原来用 setLocation 的秒杀入口和 SimpleRedisLock 也统一使用该工厂。限流、验证码消费、缓存填充/失效、补偿与关单释放等既有调用共享该改造。请求内不再触碰脚本资源的变更检查；DefaultRedisScript 的同步机制仍然存在，不能宣传为“无锁执行”。

**部署约束**：Lua 成为部署版本内的快照，改文件后必须重新构建/部署，不再依赖请求路径热加载。Redis 端仍按 EVALSHA 执行，脚本不存在时由原 Spring Data Redis 执行器回退 EVAL，服务端脚本缓存与限流计数、资格及库存是不同数据。

## 新跑的对照结果

表中均为优化前 → 优化后。每档 200 个不同用户请求、100 份免费秒杀库存，100 个有效受理/100 个库存不足，最终 100 单；0 限流、0 系统错误，全部身份集合、双库存、预约和 Inbox 对账通过。

| 并发 | 全部 HTTP 响应/秒 | 全部响应 P99 ms | 每请求 SHA 查询合计平均 ms |
| --- | --- | --- | --- |
| 8 | 24.76 → 77.74 | 544.80 → 136.99 | 246.385 → 0.006 |
| 16 | 26.37 → 149.15 | 1155.83 → 138.94 | 509.964 → 0.004 |
| 32 | 24.80 → 297.18 | 2390.93 → 125.96 | 1140.159 → 0.003 |

32 并发中，入口全部响应速率约为原来的 11.98 倍，P99 在本对样本中下降约 94.7%。这是有限批次的开发挂载环境对照，不是生产 QPS 上限或稳态订单 TPS。全部响应指标包含库存不足响应，不能把入口受理和正式履约混为一谈。

固定条件：同一 Windows Python 负载器、8081 应用/18086 发布端口、相同 Java/JVM 堆、并发档、请求量、库存、鉴权、限流、测试探针与自动 Stream→Outbox→真实 RocketMQ→MySQL 履约。两轮实际 CONFIG GET 均为 AOF yes / appendfsync always；分别新建隔离依赖并预热。9 个 Lua 文件 SHA-256 完全相同，9 份新旧脚本的正文及脚本 SHA 对比测试通过。

局限：仅一对，先旧后新，未反转顺序或计算置信区间；源码挂载文件系统、共享宿主机及观测开销都可能影响数字。此前的定位使热点指向资源检查路径，这次改造支持该路径是当前开发环境的重要限制；尚未分别量化文件 I/O 与锁等待，也未测正式打包 JAR，不能声称生产同样提升。

## 正确性与回退验证

- 4 项新单测：资源仅读取一次，后续 SHA 查询不读取资源元数据；9 份脚本文本与 SHA 不变；缺失脚本初始化失败；空白脚本初始化失败。
- 1 项真实 Redis 新集成用例：清空脚本缓存后确认 SHA 不存在，执行后重新存在；再次清空后重新执行依然触发限流拒绝，计数为 2 且 TTL 保留，证明没有绕过限流或清空计数。该用例必须显式开启，只在专用隔离 Redis 上运行。
- 加上缓存分层、权限、验证码、订单和消息回归，合计 22 项（含上述 5 项新增测试），均无失败、错误或跳过。Checkstyle 与 Java 8 构建通过。
- 两轮真实请求预热也观察到脚本缓存未命中后回退，分别 4/7 次，最终预热 20 单均正确。两轮测量共 1200 请求、600 单，另有 40 次预热不计入；它们是同一个 profile JUnit 用例的两次执行。
- 专用回归和压测环境全部清理。业务数据库、运行容器、持久化配置未更新，原 Phase 6B 主报告保留；代码未 push。

## 复验与证据

```powershell
.\scripts\verify-redis-script-loading.ps1
.\scripts\measure-independent-transaction.ps1 -NoUpdateLatest
python scripts/analyze-request-stages.py docs/transaction-profiles/<runId>
```

第一条只对独立 citymate-script-loading-p6 项目执行 SCRIPT FLUSH，不要把测试步骤复制到业务 Redis。第二条测当前优化版；本次优化前报告已归档，不应拿当前代码的新测量冒充旧版本。

- [验证与源码/脚本摘要](phase6-lua-loading-verification.json)
- [优化前应用报告](transaction-profiles/6ec2cde25523432e9def90390d1ae6f2/application.json)
- [优化前分段摘要](transaction-profiles/6ec2cde25523432e9def90390d1ae6f2/request-stage-summary.json)
- [优化后应用报告](transaction-profiles/3faa5c39ac0a4c5d9281e0996a3d79c5/application.json)
- [优化后分段摘要](transaction-profiles/3faa5c39ac0a4c5d9281e0996a3d79c5/request-stage-summary.json)
- 两个运行目录均保留独立 observations.json；历史报告未覆盖。

下一步优先在正式打包 JAR 环境复核，区分开发挂载目录效应；随后做真实进程故障和恢复对账。暂不扩展 Agent 或增加中间件。

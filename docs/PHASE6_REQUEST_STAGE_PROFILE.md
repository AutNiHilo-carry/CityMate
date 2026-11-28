# Phase 6D：秒杀请求分段计时与脚本 SHA 热点

2026-11-07。本轮完成性能诊断，未修改生产业务逻辑，未宣称优化收益。

## 结论与数据

在当前 Windows 源码挂载到 Docker 的测试环境，服务端请求的大部分耗时落在 RedisScript.getSha1() 路径。32 并发下每请求三次 SHA 查询合计平均约 1117.84 ms，占服务端累计请求时间 94.3%。不是 Redis 服务端执行 Lua 花了 1.1 秒，也不是连接池已经耗尽的证据。

下表是每请求平均耗时（ms）。占比为同一组所有请求对应跨度总和 / 服务端 Filter 耗时总和；各列为互斥区间，不能把不同分位数直接相加或相减。

| 并发 | 服务端 Filter | SHA 路径 | SHA 占比 | Redis 调用与取连接 | 剩余服务端耗时 |
| --- | --- | --- | --- | --- | --- |
| 8 | 307.91 | 239.35 | 77.7% | 66.39 | 2.16 |
| 16 | 622.11 | 554.99 | 89.2% | 65.68 | 1.44 |
| 32 | 1184.92 | 1117.84 | 94.3% | 65.76 | 1.32 |

每档 200 次 HTTP 请求、100 份库存，100 个有效受理与最终落库订单、100 个库存不足响应，无系统错误；订单 ID 集合、资格、预约、Inbox、数据库/Redis 库存完整对账。三档合计 600 次测量请求、300 单，另有 20 次预热。

入口 P99 约 528/1119/2401 ms；它含库存不足响应，不代表有效受理容量。这一组不能与旧组直接作优化前后对比，因为探针和运行时间均不同。

## 如何定位

1. 首轮只测连接获取、鉴权 HGETALL/续期、用户/全局限流、ID INCR 和秒杀 Lua。32 并发服务端平均 1244.95 ms，其中已测 Redis 区间约 65.05 ms，剩余约 1179.89 ms，不能解释为 Redis 慢。
2. 补测脚本 getSha1/getScriptAsString 后，32 并发剩余平均约 1.32 ms；每档 getSha1 被调用 600 次，即每请求用户限流、全局限流和秒杀脚本三次。
3. 已核对项目实际依赖 Spring Data Redis 2.6.2 的 DefaultRedisScript 字节码：getSha1 在同步区间检查 ScriptSource.isModified，必要时重新计算 SHA。当前 RedisScripts.number 与秒杀入口都通过 ClassPathResource 设置脚本来源。

这证明当前测量中的热点在 SHA 查询路径。资源 lastModified 检查与锁竞争可能共同影响该路径，但尚未单独测文件系统 I/O、持锁及等待时间，不能声称已经精确拆分其原因。尤其当前是 Windows 目录挂载、Maven 测试 JVM，不是正式 JAR 的生产部署。

## 探针范围与正确性

- RequestStageProbe 只存在于 src/test，由独立 profile 用例显式 Import；不进入正式业务 JAR。
- Filter 建立请求线程内上下文，finally 清理；仅采集本次秒杀 POST。其他请求、后台履约和监控采样不进入请求跨度。
- 测试包装 RedisConnectionFactory 和 StringRedisTemplate，计时同步客户端调用、取连接与脚本 SHA/正文读取；不保存 Token、Key、脚本正文或响应正文。计时包含相应客户端等待，不能当 Redis 服务端 CPU 时间。
- 归档保留每请求服务端耗时与阶段调用数。协调测试断言所有请求都有六个业务 Redis 阶段、无非预期 Redis 异常；正常 NOSCRIPT 回退单独计数。分析脚本另外验证非重叠区间和每档 600 次 SHA 调用。
- 两轮都保持真实鉴权、限流、自动 Stream→Outbox→真实 RocketMQ→MySQL 履约，Redis AOF always 不变。
- Checkstyle 与 Java 8 构建通过；两次同一个 JUnit 协调用例执行通过，共 1200 次测量请求、600 单，非两个唯一测试用例。最初一次因编译输出缺少 UserHolder.class 而退出，没有进入负载，清理 target/classes 与 target/test-classes 后重建；失败轮次不计性能结果。
- 两轮环境均自动清理，原 Phase 6B 主报告保持字节一致；没有重启业务容器，没有 push。

## 下一步：保持语义的优化对照

优先验证将应用内不可变 Lua 在初始化时读取一次、用内存脚本正文构建 DefaultRedisScript，减少请求内资源变更检查。脚本变化由重新构建/部署生效，而不是请求热加载；仍使用同一 Lua、鉴权、限流、资格预占与 always 持久化语义。

实施后需测试真实脚本缓存清空的 NOSCRIPT 回退，确认不是绕过执行；在同条件的新隔离运行中重复基线/优化组并核对订单集合与双库存。还需用正式打包 JAR 区分挂载目录效应，再决定简历中能写什么收益。本轮仅完成定位，没有实施上述生产优化。

## 复验与原始证据

```powershell
.\scripts\measure-independent-transaction.ps1 -NoUpdateLatest
python scripts/analyze-request-stages.py docs/transaction-profiles/<runId>
```

分析脚本拒绝覆盖已存在的摘要。服务端与客户端未逐请求关联 ID，不能拿两边不同请求的分位数相减。探针存在额外开销，样本是每并发档一批，不是最大容量或稳态 TPS。

- [验证汇总](phase6-request-stage-verification.json)
- [补测原始报告](transaction-profiles/92502feab3584c9da7b15f7264537bd3/application.json)
- [补测阶段摘要](transaction-profiles/92502feab3584c9da7b15f7264537bd3/request-stage-summary.json)
- [补测资源观测](transaction-profiles/92502feab3584c9da7b15f7264537bd3/observations.json)
- [首轮阶段摘要](transaction-profiles/ff4d5136ae5c492db318208af060a643/request-stage-summary.json)

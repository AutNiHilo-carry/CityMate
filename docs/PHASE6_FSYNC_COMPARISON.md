# Phase 6C：Redis fsync 策略单变量诊断

2026-11-06。对照编号 `9477aa52687740b89256787695bffce1`。已重新执行 always 和 everysec 两档，而不是拿历史记录充当对照；每档通过一个显式启用的协调测试，共两次执行、同一个用例。

## 结果

表内数值顺序均为 **always / everysec**。每档每个并发级 200 个不同用户 HTTP 请求、100 份免费秒杀库存；每组有效受理和最终订单均为 100，系统错误为 0，库存、预约、Inbox、受理与落库 ID 集合均核对通过。合计 1200 次测量请求、600 单；另外 40 次预热不计入。

| 并发 | 全部 HTTP 响应/秒 A/B | 全部响应 P99 ms A/B | 观察完成速率 单/秒 A/B |
| --- | --- | --- | --- |
| 8 | 22.24 / 22.87 | 689.78 / 709.11 | 6.99 / 7.36 |
| 16 | 23.42 / 23.67 | 1218.24 / 1285.58 | 6.36 / 7.32 |
| 32 | 21.47 / 21.52 | 2879.47 / 2872.52 | 6.71 / 5.93 |

三档入口响应速率差异很小，P99 没有一致下降，观察完成速率也没有稳定提升。**本轮不支持把 Redis fsync 模式认定为主要性能限制。**这不是证明持久化永远没有影响，更不是把 everysec 当作已完成的生产优化。

## 对照约束

- 两档分别重建隔离 MySQL/Redis/RocketMQ，重新预热；原业务 Compose 和持久化配置没有修改。
- 仅有意改变测试 Redis 的 appendfsync，AOF 两档均开启。测试用 CONFIG GET 读取实际模式并断言，不只相信启动参数。
- 同一独立 Windows Python 负载器、发布端口 18086、相同 Java 8/JVM 堆、请求量、库存、并发档和指标采样方式；保留鉴权、限流及自动 Stream→Outbox→真实 MQ→MySQL 履约。
- 原单 JVM 基线和 Phase 6B 独立观测的原始主文件保持原样，两个新运行各自归档，不覆盖旧数据。
- always 先跑、everysec 后跑，只有一对，未反转顺序或计算置信区间；宿主机负载、文件缓存、Maven 父进程占用可能不同。因此只能作有边界的诊断，不能宣称精确排除所有持久化影响。

指标仍沿用 Phase 6B 口径：全部响应/秒含库存不足响应，不是有效受理吞吐；P99 含拒绝响应；观察完成速率包含跨进程协调和轮询，不是消费者稳态 TPS。

## 持久性不等价

Redis 官方说明，always 在 AOF 追加后同步并支持批量提交；everysec 每秒同步，故障时可能丢失最近约一秒的写入。不能从正常负载对账通过，推导崩溃时也不丢请求。参见 [Redis 持久化说明](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)。本轮未做断电或真实崩溃试验，业务环境保持原策略。

## 下一步方向

入口还有多个独立的同步 Redis 调用：鉴权读/续期、用户和全局限流、订单号递增、秒杀 Lua。当前 hmdp.seckill.admission Timer 仅包围最后的秒杀 Lua，不能解释全部 HTTP 延迟。

优先对鉴权、限流、ID 生成、Lua 分段计时，关联客户端连接/线程等待；同时继续检查 Outbox 的数据库交互。先验证耗时落在哪个阶段，再选择保持业务和持久性语义的优化。不要基于这次结果盲目扩连接池、换中间件或降低数据安全要求。

## 复验与证据

```powershell
.\scripts\compare-redis-fsync.ps1
```

单独执行一档可用 `measure-independent-transaction.ps1 -RedisFsync always` 或 `-RedisFsync everysec`，默认仍是 always。对照脚本使用 -NoUpdateLatest，不覆盖原基线主报告。

- [运行对照清单](phase6-fsync-comparison-pair.json)
- [校验、环境指纹和差异](phase6-fsync-comparison-verification.json)
- A：`docs/transaction-profiles/f7ad3592261d411c8c5d1aad791aff85` 内的 application.json / observations.json
- B：`docs/transaction-profiles/abe2ea943f874807b8f2beed232b4def` 内的 application.json / observations.json
- 本对照归档：`docs/fsync-comparisons/9477aa52687740b89256787695bffce1`

两档专用应用、依赖和卷均已清理。测试源码只增加配置核验与按运行编号写报告；没有修改生产 Java/Lua/SQL，未更新业务容器，未 push。

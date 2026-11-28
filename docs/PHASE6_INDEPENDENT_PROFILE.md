# Phase 6B：独立负载进程与资源观测

2026-11-06，运行编号 `5726d1830c7d4c96bd56e9fca58f0d67`。应用在独立 Docker JVM 中运行，Windows Python 进程通过仅绑定 127.0.0.1 的 18086 端口发 HTTP；没有在应用 JVM 中运行请求线程池。仍共享同一宿主机，不是分离物理机器的容量认证。

## 实测

每档 200 次不同用户请求、100 份免费秒杀库存；每档受理和最终订单均为 100，库存不足 100，HTTP 系统错误、限流与重复买家为 0，订单 ID / 预约 / Inbox / DB 与 Redis 库存对账通过。另有 20 次预热，不计入表内。

| 并发 | 全响应/秒 | 全响应 P95 ms | 全响应 P99 ms | 观察完成速率 单/秒 |
| --- | --- | --- | --- | --- |
| 8 | 22.78 | 562.38 | 638.54 | 6.79 |
| 16 | 23.19 | 1105.24 | 1340.95 | 6.87 |
| 32 | 21.72 | 1607.74 | 2667.65 | 6.61 |

全响应速率包含库存不足，不能当作有效受理吞吐；全部响应延迟也包含拒绝，不含生成器队列等待。观察完成速率从应用发出就绪信号起，到收到请求结果并观测到全部履约，包含跨进程协调及 250ms 对账轮询，不是消费者稳态 TPS。

独立负载器、端口映射和采样均改变了测量条件，不能拿它与 Phase 6A 的差值宣称优化效果。每档一次有限批次，没有最大容量或置信区间结论。

## 资源证据

- 19 个有效 Prometheus/Docker 采样点，无读取错误。采样中的 Hikari pending 最大 0、active 最大 4；这不证明采样间隙从未出现等待。
- JVM 采样堆占用最大约 88.3 MiB；各 GC 累计序列的首末增量合计约 0.0790 秒。它们不足以支持“GC 是主要根因”的结论，也不能排除未采到的瞬态。
- 采样窗口内 `hmdp.seckill.admission` Timer 的平均约 35.49ms。这个 Timer 只包围入口秒杀 Lua 调用，不包含之前的鉴权刷新、限流和订单号生成，不能代表整个 HTTP 请求。
- 32 并发窗口中 SQL 摘要累计耗时靠前的是 COMMIT、Outbox 续租与标记发送。原始计时值和次数保存在汇总 JSON，未擅自折算成用户端耗时；窗口也含后台其他订单事件，不能当作每单固定成本。
- Redis EVALSHA 服务端命令统计的耗时不能覆盖客户端连接等待、网络和持久化等待；Lua 指令执行快不意味着整个入口快。

容器 CPU 和内存原始值在采样中保留。Docker CPU 百分比不能直接当成整机利用率；应用容器还含 Maven 父进程，且采样本身会增加开销。

## 当前判断

并发继续增加仍未提升完成速率，尾延迟升高。现有证据更值得继续检查入口的多次同步 Redis 往返、AOF always 环境下的等待，以及 Outbox 发送侧的频繁持久写入；它们是有证据支撑的排查方向，不是已证明的唯一根因。

下一步应在隔离环境做单变量诊断，例如区分持久化等待与应用往返开销，并保留原数据、同样的对账及业务语义。不能直接把 Redis/MySQL 持久化降级后的数字包装成生产优化；本轮没有改任何生产逻辑或持久化配置。

## 工具与边界

```powershell
.\scripts\measure-independent-transaction.ps1
```

脚本先启动专用 MySQL/Redis/Memcached/RocketMQ，再后台启动显式启用的 `IndependentTransactionProfileIntegrationTest`；Python 等就绪文件后发压，Java 用真实数据库核对结果。测试默认禁用，需 `-Dcitymate.profile=true` 和唯一运行编号。正式服务没有新增夹具接口。

Python 读取受测试专用令牌保护的 Prometheus，定期执行 Docker stats；Java 在各档前后读取 Redis commandstats、MySQL global_status 与 performance_schema SQL 摘要。登录态为隔离夹具，不测试短信或真实支付；Kafka、CDC、AI 关闭。

原始应用数据：[profile](phase6-independent-profile.json)，资源样本：[observations](phase6-independent-observations.json)，派生汇总：[verification](phase6-independent-profile-verification.json)。不可变运行目录在 `docs/transaction-profiles/5726d1830c7d4c96bd56e9fca58f0d67`，协调文件和应用日志位于 target 下。

测试用例 1 项通过，0 失败、错误和跳过；不是把 600 次请求计成 600 项功能测试。专用应用、依赖和卷已清理，业务环境未更新，未 push。后续真实进程故障和容量验证仍待完成。

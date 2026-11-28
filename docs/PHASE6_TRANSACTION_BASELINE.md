# Phase 6A：正常交易有限批次基线

验证日期：2026-11-06。Checkstyle、Java 8 目标编译通过；1 项显式启用的基线用例通过，无失败、错误或跳过。它执行预热及三组负载，不应作为新增大量功能测试计数。生产业务逻辑未修改。

## 实测结果

每组 200 个不同用户请求、100 份免费秒杀券库存。三组均有效受理 100、库存不足 100，限流 0、系统错误 0，最终订单 100；预热另外 20 次，不计入下表。

| 并发 | 全部 HTTP 响应/秒 | 有效受理/秒 | 全部响应 P95 ms | 全部响应 P99 ms | 观察完成速率 单/秒 |
| --- | --- | --- | --- | --- | --- |
| 8 | 24.26 | 12.13 | 542.03 | 599.90 | 7.01 |
| 16 | 29.18 | 14.59 | 835.30 | 997.82 | 7.05 |
| 32 | 27.36 | 13.68 | 1278.58 | 2102.33 | 7.02 |

原始数据：[请求样本、积压与对账](phase6-transaction-baseline.json)。[汇总校验](phase6-transaction-baseline-verification.json)。不与 2026-10-11 的商户热点查询 QPS 比较：端点、工作内容和测量环境不同。

## 范围与环境

- 真实 HTTP POST /voucher-order/seckill/{id}，保留鉴权、限流和 Lua，不通过 Java 直接调用接口替代 HTTP。
- 隔离用户登录态由夹具写入 Redis，未测试短信和登录接口吞吐。券价格为 0，没有真实收款。
- 后台自动执行 Stream 消费、持久预约、Outbox、真实 RocketMQ、Inbox 和 MySQL 履约；未在负载过程中手动代替消费者逐条处理。
- 专用 Compose 项目 citymate-transaction-p6、数据库 hmdp_optimization_test；MySQL 8.0.46、Redis 7 镜像、RocketMQ 5.3.2，Redis AOF appendfsync always。
- JVM 1.8.0_504，容器可见逻辑 CPU 28，Xmx512m，Runtime.maxMemory 为 477626368 字节。CPU 数不是独占物理核心承诺。
- 负载发生器与应用共享测试 JVM，用本机 loopback HTTP，不经过 Nginx。数据库、Redis、Broker 为真实独立容器；Kafka、Canal、AI 关闭。
- 数据准备、Spring 启动和预热不计入测量批次；每个并发档只有一次样本，没有容量上限、持续稳态或置信区间结论。

## 指标口径

全部 HTTP 响应/秒 = 200 / 整批请求完成时间，包含库存不足与限流响应；它不是有效订单吞吐。有效受理/秒 = 唯一受理数 / 同一入口批次时间。

P50/P95/P99 来自每次 HTTP 连接、响应读取和解析的耗时，含拒绝响应；不包含任务在发生器队列中尚未执行 HTTP 的等待。原始样本全部保留，不能称为仅成功请求的延迟。

观察完成速率 = 订单数 / 从首个请求提交到观测到全部履约的时间。250ms 轮询、发生器记账和观测开销会影响结果；这是有限批次的端到端观察值，不是独立测得的消费者稳态 TPS。

每组最终核对：受理 ID 集合等于落库 ID 集合，订单数 = SUCCESS 预约数 = fulfillment Inbox 数，数据库剩余库存 = Redis 剩余库存 = 初始库存 - 唯一受理数，无重复买家；并等待本组履约任务完成、共享 Stream pending 清空。保存履约事件和 pending 的观测轨迹，不把所有业务/分析任务都宣称清空。

## 结论与下一步

本机条件下，并发由 16 提高至 32 没有提高观察完成速率，P99 升高。只凭这三组结果无法确定是 Redis AOF、SQL、连接池、Outbox 调度、Broker 还是共享 JVM 资源造成的限制，不把猜测写成已定位的瓶颈。

下一步先补独立应用/负载器和资源观测，重复测量，关联 Redis 调用耗时、慢 SQL/锁等待、线程/连接池、GC 及 Relay 积压，确定瓶颈后再做同条件优化对照；之后开展真实进程故障与恢复演练。本次没有注入真实故障，也没有性能优化前后结论。

## 复验

```powershell
.\scripts\measure-transaction-baseline.ps1
```

需要 Docker，脚本启动专用 MySQL/Redis/Memcached/RocketMQ，初始化 Topic，显式设置 -Dcitymate.baseline=true。基线类默认不执行；仅 BUILD SUCCESS 不足，必须确认 JUnit 非跳过且报告为本次生成。

脚本默认清理专用测试环境；保留失败现场可用 -KeepEnvironment。再次运行前会归档已有原始报告，最新报告会覆盖主文件；归档保存在 docs/transaction-baselines。需保留失败过程时一并保存 target/phase6-transaction-baseline.log 和 JUnit XML。

业务环境、密钥和数据库未改，未更新业务容器，未 push。

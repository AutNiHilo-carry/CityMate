这是 CityMate 项目的最新方向调整，请以后以这个版本为准。

## 一、项目底座保持不变

CityMate 继续以我自己升级过的黑马点评为基础进行改造。

暂时不切换到：

- Spring Cloud 微服务
- Nacos / Gateway / Sentinel / Seata 全家桶
- 分库分表
- Kubernetes
- 复杂多级缓存体系

原则是：

**保持单体架构，控制范围，把核心后端问题做深。**

不通过“堆技术栈”制造复杂度。

---

## 二、重新定义项目定位

CityMate 不再只是：

“黑马点评升级版”。

更准确的定位是：

**CityMate：面向本地生活场景的高并发交易与可靠订单平台。**

如果用于简历，可以进一步强调：

**围绕优惠券秒杀链路，对缓存一致性、库存预扣、可靠消息、订单幂等和异常补偿进行工程化改造，并通过压测和故障测试验证方案。**

项目的真正价值不是功能数量，而是：

- 高并发
- 一致性
- 可靠消息
- 幂等
- 补偿
- 故障恢复
- 可验证性

---

## 三、P0 核心主线

未来优先完成以下四条主线。

### 1. 缓存一致性

不要只停留在“使用 Cache Aside”。

需要真正研究和实现：

读路径：

Cache  
→ miss  
→ MySQL  
→ rebuild cache

写路径：

MySQL Transaction  
→ delete / invalidate cache

需要重点处理：

- 删除缓存失败怎么办
- 并发读写导致短暂不一致怎么办
- 哪些业务允许最终一致
- 哪些数据不应该缓存
- TTL 如何设置
- 缓存重建并发怎么办
- 热点 Key 怎么处理
- 是否需要延迟双删或其他补偿
- 如何验证一致性策略有效

这部分要通过并发测试和故障测试验证，而不是只背方案。

---

### 2. 秒杀资格与库存

保留 Redis + Lua 的高并发预检思路。

典型链路：

请求  
→ Redis Lua  
→ 活动资格校验  
→ 一人一单校验  
→ 库存校验  
→ Redis 库存预扣  
→ 生成订单事件

重点研究：

- 为什么不用 Java synchronized
- 为什么不用数据库直接处理全部请求
- Lua 的原子性解决什么问题
- Redis 库存和数据库库存分别代表什么
- Redis 预扣成功但订单创建失败怎么办
- 如何避免重复下单
- 数据库为什么仍然需要唯一约束
- 并发情况下如何避免超卖
- Redis 出现异常时如何降级或失败

不要把“用了 Lua”当作项目亮点。

真正的亮点是：

**秒杀链路的正确性和异常处理。**

---

### 3. 可靠订单链路

这是 CityMate 最核心的个人工程贡献之一。

不要只实现：

Redis  
→ MQ  
→ MySQL

而是形成完整链路：

Redis 资格 / 库存预扣  
→ Order Event  
→ 可靠消息  
→ Consumer 幂等  
→ DB Transaction  
→ 创建订单 + 扣减数据库库存  
→ ACK

必须重点处理：

- MQ 发送失败
- 消息重复
- 消费失败
- Consumer 宕机
- DB 事务失败
- ACK 失败
- 消息积压
- 重试
- 死信 / Pending
- 重复消费
- 最终数据不一致

重点实现并理解：

- 幂等
- 数据库唯一约束
- Retry
- Compensation
- Reconciliation
- 最终一致性

不要只证明 Happy Path 能跑通。

---

### 4. 压测 + 故障测试 + 数据验证

CityMate 必须有真实实验。

建立多个版本或 baseline，例如：

V1：MySQL 直接下单 / 扣库存  
V2：Redis Lua 预检  
V3：异步订单  
V4：加入可靠性机制

尽量统一测试环境，记录：

- QPS
- P50
- P95
- P99
- 错误率
- DB QPS
- MQ 积压
- 最终订单正确率
- 超卖情况
- 重复订单情况

主动进行故障注入，例如：

- kill Consumer
- Redis timeout
- MQ send failure
- DB transaction failure
- duplicate message
- cache delete failure

记录：

故障发生  
→ 系统表现  
→ 是否重试  
→ 是否补偿  
→ 是否恢复  
→ 最终数据是否正确

不要在简历中写没有真实测试支撑的性能数据。

---

## 四、订单状态机

将订单生命周期作为重要 P1，条件允许时做到接近 P0。

例如：

CREATED  
→ PENDING_PAYMENT  
→ PAID  
→ COMPLETED

异常：

PENDING_PAYMENT  
→ timeout  
→ CANCELLED  
→ release stock

重点处理：

- 超时未支付
- 库存释放
- 重复支付通知
- 重复取消
- 已支付订单能否被取消
- 并发状态更新
- 补偿任务重复执行
- 状态迁移是否合法

这一部分要把：

**事务 + 幂等 + MQ + 补偿**

串成一条完整业务链。

---

## 五、AI / Agent 的定位调整

CityMate 中的单 Agent + 轻量 RAG 保留，但降低为 P1。

不要让 CityMate 和 ResearchDesk 在 Agent 能力上重复。

CityMate 中的 AI 更适合作为真实业务功能，例如：

用户提出本地生活需求  
→ 检索平台商户 / 活动信息  
→ Agent 进行推荐和解释

重点是：

**AI 如何接入真实后端业务。**

而不是：

- Multi-Agent
- 复杂 Agent Memory
- Agent Harness
- 高级 Skill 演进

这些由 ResearchDesk 负责。

如果投 Java 后端岗位，AI 模块甚至可以弱化。

如果投 AI 应用岗位，再突出这一部分。

---

## 六、明确暂时不做的内容

除非出现真实需求，否则不要主动增加：

- 微服务拆分
- Spring Cloud
- Nacos
- Gateway
- Sentinel
- Seata
- 分库分表
- Kubernetes
- Elasticsearch 大规模搜索体系
- OpenResty 多级缓存
- 大量中间件

如果以后系统真实出现：

- Redis Hot Key
- 单体模块边界问题
- 数据量瓶颈
- 搜索瓶颈

再针对问题引入对应方案。

原则是：

**问题驱动技术，而不是技术驱动项目。**

---

## 七、必须区分教程能力和个人贡献

原版黑马点评已经包含很多经典能力，例如：

- Redis 缓存
- Lua
- 分布式锁
- 一人一单
- Stream
- GEO
- Feed

以后简历和项目介绍时，不能把教程本身已有的东西全部包装成个人创新。

CityMate 的个人贡献应该主要体现在：

- 缓存一致性策略改造
- 秒杀异常链路
- Redis / DB 库存一致性
- 可靠消息
- Consumer 幂等
- 订单补偿
- 对账
- 状态机
- 故障注入
- 压测和对照实验
- 真实性能与正确性数据

如果某个模块来自原项目，需要明确说明：

“基于原有 X 能力，我针对 Y 问题进一步设计了 Z，并通过实验验证。”

---

## 八、项目开发优先级

按照下面顺序推进。

### Phase 1：整理现有底座

确认黑马点评已有功能：

- 哪些是原教程能力
- 哪些是我已经升级的
- 哪些准备删除
- 哪些保留

先明确个人贡献边界。

### Phase 2：缓存正确性

完成：

- Cache Aside
- 一致性策略
- 异常处理
- 并发测试

### Phase 3：秒杀主链

完成：

- Lua 资格校验
- 一人一单
- Redis 库存预扣
- 防超卖

### Phase 4：可靠订单

完成：

- MQ
- 可靠发送
- 幂等消费
- DB 事务
- Retry
- Compensation

### Phase 5：订单生命周期

完成：

- 状态机
- 超时取消
- 库存回补
- 对账 / reconciliation

### Phase 6：实验验证

完成：

- 压测
- 故障注入
- 一致性测试
- 指标记录
- Baseline 对照

### Phase 7：有余力再做

2026-11-09 进展：后端选定回归和故障验证完成后，已开展 [单 Agent 只读查询](PHASE7_READ_ONLY_AGENT.md)。以下为原方向规划，不代表各项均未实现，也不代表扩展真实支付或多 Agent。

- 轻量 Agent / RAG
- Hot Key 优化
- Caffeine 本地缓存
- 其他真实瓶颈优化

---

## 九、项目架构原则

CityMate 最重要的主链应该是：

查询场景  
→ Redis  
→ MySQL

秒杀场景  
→ Redis Lua  
→ 库存预扣  
→ Order Event  
→ MQ  
→ Consumer  
→ DB Transaction

可靠性层：

- Idempotency
- Retry
- Compensation
- Reconciliation

验证层：

- Load Test
- Fault Injection
- Consistency Check
- Metrics / Logs

项目重点应该围绕这条链展开。

---

## 十、和 ResearchDesk 的职责边界

CityMate 主要证明：

**传统后端可靠性能力。**

核心关键词：

- Concurrency
- Cache
- Transaction
- Consistency
- Messaging
- Idempotency
- Compensation

ResearchDesk 主要证明：

**Agent 系统可靠性能力。**

核心关键词：

- Agent Harness
- Tool Reliability
- Recovery
- Evidence
- Evaluation

两个项目不要互相重复。

最终共同体现：

**我不仅能实现功能，还关注系统在失败、并发和不确定情况下是否仍然可靠。**

---

## 十一、最终目标

CityMate 最终不是要证明：

“我会黑马点评。”

也不是：

“我用了很多 Java 中间件。”

而是：

**我基于一个真实本地生活业务，对高并发交易链路进行了工程化改造，深入处理缓存一致性、库存、可靠消息、订单幂等和异常补偿，并通过真实压测与故障测试验证系统行为。**

所有后续功能和技术选型都应该服务于这个目标。

如果新增一个技术不能明显提高：

- 工程深度
- 项目真实性
- 面试价值
- 可验证性

就暂时不要加入。

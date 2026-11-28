# CityMate 逻辑审计与修复（2026-11-09）

本轮完成跨模块检查并修复 **6 类问题**。最终基础组 **161 项**、真实中间件组 **13 项**，共 **174 个不重复后端用例**通过，失败/错误/跳过均为 0；前端 18 项测试与语法检查通过。范围是选定回归和人工逻辑检查，不是全项目无漏洞证明。

## 发现与修复（按影响排序）

### A1 · P1：原始 URL 与实际路由不一致，普通用户可进入管理写接口

AccessGuard 原先按 getRequestURI 检查 /shop 和 /ops/ 前缀；Spring MVC 会处理分号路径参数。普通登录用户的 PUT `/shop;v=1` 在独立 MVC 复现中得到 200，服务层更新方法被调用，而不是 403；类似规范化差异也影响运维前缀与接口限流。

修复：认证、管理权限、限流和操作审计统一使用 UrlPathHelper.getPathWithinApplication 规范化应用路径，剥离 context path 与分号参数。实际 HTTP 回归已核验非管理员的 `/shop;v=1` 写入和 `/ops;v=1/outbox` 查询均为 403。

代码：[AccessGuard](../src/main/java/com/hmdp/reliability/AccessGuard.java)；回归：AccessGuardRoutingTest、ReliabilityIntegrationTest.httpAuthorizationAndPendingRequestOwnershipAreEnforced。管理员正常更新也保留通过。

### A2 · P1：监控过滤器可因分号路径跳过认证

MetricsAccessFilter 原先判断 servletPath/原始 URI 是否以 /actuator/ 开头。`/actuator;x=1/prometheus` 会漏过过滤器。过滤器级复现期望 401，实际得到 200 且请求进入后续链路；不能用这一步单独声称所有生产部署都已泄露指标。

修复：同样按规范化应用路径判断过滤范围。过滤器回归和当前嵌入式服务器的真实 HTTP `/actuator;v=1/prometheus` 请求均验证未认证时为 401。现有监控凭据与管理员访问语义保留。

代码：[MetricsAccessFilter](../src/main/java/com/hmdp/cache/MetricsAccessFilter.java)；回归：MetricsPathSecurityTest 与上述实际 HTTP 用例。

### A3 · P1（异常库存前提）：补偿 Lua 报错前已经删除恢复身份

原脚本只用 tonumber 校验库存，然后 HDEL owner、SREM 资格，最后 INCR。Redis Lua 遇到后续命令报错不会撤销此前写入。真实 Redis 复现将库存改为 `0.5`：INCR 失败，但原 owner 已变为 null。后续任务可能把 owner 缺失解释为无需再补偿，造成库存未归还却丢失恢复依据。此问题需要 Redis 库存值损坏或异常，并非正常整数路径必现。

修复：要求非负整数字符串，在校验原 owner 后先执行 INCR，成功才删除身份。若小数格式或有符号 64 位溢出导致失败，owner/资格保留，任务不完成；修复库存后仍能按原事件补偿一次。回归同时覆盖 `0.5` 和 `9223372036854775807`，并验证恢复后库存 1、Inbox 1、事件 DONE。

代码：[seckill-compensate.lua](../src/main/resources/seckill-compensate.lua)；回归：SeckillCompensationIntegrationTest.invalidNumericStockDoesNotDestroyTheOwnerNeededForRetry。

### A4 · P2：关单异常中断批次，满页异常可能长期挡住后续订单

原扫描循环不隔离单笔异常，首笔抛错后本批后续订单不再执行；固定读取最早 200 条也会让满页异常记录挡住后续订单。

修复：每笔订单保持自己的事务，逐笔捕获 RuntimeException 并记录原订单和失败摘要；采用按 ID 的 200 条游标扫描，扫描到尾部后回到起点重试，单实例方法同步防止本地游标竞争。数据库查询本身失败仍会抛出，由定时任务下轮重试；不吞掉 JVM 致命错误。排序从到期时间改为 ID，意味着没有按最早到期严格优先或恢复时限的承诺；持续积压和多实例调度仍需额外容量设计。

代码：[OrderLifecycleServiceImpl](../src/main/java/com/hmdp/service/impl/OrderLifecycleServiceImpl.java)、[Mapper SQL](../src/main/resources/mapper/VoucherOrderMapper.xml)。回归覆盖单笔异常后的下一单、整页 200 笔异常后的第 201 单及扫描复位，并保留原有关单集成场景。

### A5 · P2：失败普通券请求仍提交数据库串行化槽位

普通券先 INSERT IGNORE 串行化槽位，再检查券是否存在、上架和类型。原逻辑返回 Result.fail 并不触发事务回滚，因此无效券 ID 请求也可积累 tb_ordinary_order_slot 行。

修复：无效券分支显式设置 rollbackOnly，保留先锁槽位再读业务状态的顺序，避免为了提前校验而改变一致性读取时点。事务回归采用实际 AbstractPlatformTransactionManager 语义，核对失败时 rollback 1 次、commit 0 次。此回归不等同于对普通券接口做了滥用压测。

代码：[VoucherOrderServiceImpl](../src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java)；回归：VoucherOrderReliabilityTest.nonexistentOrdinaryVoucherDoesNotCommitItsSerializationSlot。

### A6 · P2：旧商户查询接口缺少分页与坐标边界

组合搜索已有校验，但旧分类/GEO 和名称接口仍接受空/负页码、极大页码、单边坐标或 NaN 等。GEO 路径的 int 页码乘法可能溢出，参数也可能到 Redis/MySQL 才报错。

修复：旧接口分页限定 1～10000，分类 ID 为正；附近查询坐标成对、有限，经度在 [-180,180]，纬度按 Redis GEO 的 [-85.05112878,85.05112878]；名称长度最多 100。参数在依赖调用前被拒绝。该项收紧的是旧接口契约，组合搜索接口的既有范围没有改变。

代码：[ShopServiceImpl](../src/main/java/com/hmdp/service/impl/ShopServiceImpl.java)；回归：ShopSearchBoundaryTest 的两个 legacy 场景，覆盖 Integer.MAX_VALUE、NaN、缺失坐标和超长名称。

## 检查覆盖与证据强度

| 范围 | 本轮工作 | 边界 |
| --- | --- | --- |
| 权限、身份与本人数据 | 检查 MVC/Actuator 两套入口、ThreadLocal 清理、本人订单、管理校验；实际 HTTP 与单元回归 | 不是互联网渗透测试，也未验证所有容器/代理自定义路径配置 |
| 秒杀、补偿、关单 | 检查事务、SQL 条件、唯一约束、owner、Lua 部分写入与恢复；真实 MySQL/Redis 回归 | 未重跑六个历史 SIGKILL 实验，无新容量结论 |
| 缓存与 CDC | 检查版本/owner 栅栏、失效与批次确认；缓存恢复及真实 Canal 回归 | 未模拟 Redis 持久化永久丢失或多机网络分区 |
| MQ 与分析 | 检查 Outbox 租约/条件写回、Inbox、Kafka 提交与重放；真实 RocketMQ/Kafka 回归 | 不证明跨存储 Exactly Once 或生产多节点高可用 |
| RAG 与查询工具 | 检查工具白名单、服务端身份、超时队列、引用及版本复核；AI 边界测试 | 模型调用使用本地替身；未支付调用真实模型或验证回答语义准确率 |
| 上传与前端 | 检查受限路径、归属、引用与异步删除；媒体测试、18 项前端测试和语法检查 | 未做浏览器视觉/键盘验收、操作系统符号链接攻击或依赖 CVE 全量扫描 |

最终基础运行：`a56e0315f3f14dfeb09d69147237f7bf`；真实中间件运行：`db9ac0c04fb74180b115c9d56281abdb`。各组用例名称集合不相交；计数不叠加失败尝试或此前历史回归。

- [机器可读汇总](logic-audit-verification.json)
- [基础组统计](logic-audits/2026-11-09/a56e0315f3f14dfeb09d69147237f7bf/suites.json)
- [中间件组统计](logic-audits/2026-11-09/db9ac0c04fb74180b115c9d56281abdb/suites.json)
- [修复前失败断言](logic-audits/2026-11-09/reproductions.json)

JUnit 断言与时间已归档；完整调试日志和测试生成文档保留在 target/logic-audit，不覆盖历史根 docs。一次编译期重载错误、一次 Windows/Linux 换行错误、一次主动终止的慢挂载运行、一次 Kafka 用例分组错误均不计为最终通过；详见汇总 unsuccessfulAttempts。前端首次 npm.ps1 被本机执行策略拦截，随后使用 npm.cmd 完成检查，未修改执行策略。

## 复验与应用修复

```powershell
.\scripts\run-logic-audit.ps1 -Mode regression
.\scripts\run-logic-audit.ps1 -Mode broker
```

仅使用 citymate-logic-audit 专用项目和合成数据。源码复制到容器内部后运行测试，减少 Windows 挂载元数据开销；根 .env 不复制、Key 不读取。默认结束后清理专用容器与卷。真实中间件组需要本机有足够资源运行 MySQL、Redis、Memcached、RocketMQ、Kafka、Canal。

Checkstyle 与 Java 8 编译通过，旧压测原始文件保留；本轮没有更新性能指标。**尚未重新打包可执行 JAR、更新业务容器或 push**。若当前服务运行预构建 JAR，它仍是原版本；后续需明确构建并部署后，再按演示流程验收。

真实支付/退款/核销、多机高可用和外部模型工具循环仍是既有边界。本轮修复不把 AI/RAG 的现有只读问答扩充为完整 Agent。

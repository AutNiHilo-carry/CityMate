# Phase 2：缓存失效失败的恢复验证

2026-11-05 当前状态：选定的 19 项测试全部通过，0 失败、0 错误、0 跳过，包括新增三个缓存恢复场景。Checkstyle 与 Java 8 目标编译通过；独立测试环境已清理。没有修改生产 Java/Lua/SQL 逻辑，没有新压测数字。

## 新增场景

测试文件：[CacheInvalidationRecoveryIntegrationTest](../src/test/java/com/hmdp/cache/CacheInvalidationRecoveryIntegrationTest.java)。

1. **业务提交成功、失效失败、后台恢复**：预热旧缓存；更新店铺并同事务登记事件；提交后失效失败；验证 DB 新值、缓存旧值、PENDING 事件；Relay 失效仍失败，验证工作保留、无 Inbox 确认、租约释放；恢复后沿用同一事件重试并进入 DONE，新请求读新值。
2. **业务回滚**：更新与事件同事务回滚；确认没有提交后失效、没有持久事件，DB 与缓存仍为旧值。
3. **旧加载与恢复并发**：让旧数据库读取暂停；新事务提交并经历失效失败与 Relay 失败；恢复失效后再放行旧读取；允许该在途请求返回旧值，但不得回填共享缓存，后续请求必须读新值。

## 隔离与故障口径

- 写入夹具前验证数据库必须为 hmdp_optimization_test。店铺夹具为 982041；不连接开发项目的 MySQL/Redis。
- 使用独立 Compose 项目 citymate-cache-p2，只启动测试用 MySQL、Redis、Memcached。
- 通过测试专用 CacheClient 子类让指定店铺的 invalidate 抛出 RedisConnectionFailureException。数据库、Redis、Outbox、Relay、Inbox 使用真实组件；没有停止真实 Redis，也没有模拟所有网络故障。
- MQ、CDC、调度及 AI 在新用例中关闭，手动执行真实 Relay。本轮目标是持久失效与本地投影恢复，不能宣称验证了 RocketMQ 或真实 Canal 的全部故障。
- 为避免等候退避时长，测试仅对隔离事件修改 next_attempt_at；验证可重试性，不测真实退避时间或恢复 SLA。
- 自动重试有上限，进入 REVIEW 后需复核；本测试不证明任意持续故障都能无限自动恢复。
- 测试通过后才会生成 docs/phase2-cache-recovery-evidence.json 的用例轨迹。最终通过与否还必须核对 Surefire 报告，包括清理阶段错误，不能仅看轨迹中的局部 passed。

## 恢复 Docker 后复验

在项目根目录执行：

```powershell
.\scripts\verify-cache-recovery.ps1
```

脚本只针对 citymate-cache-p2。默认在退出时清理该测试项目与其临时卷，外部 Maven 缓存及 citymate 业务项目不受影响。需要保留失败现场时添加 -KeepEnvironment。

测试复用外部 Maven 缓存，缺失依赖时联网下载；不能因依赖缺失跳过测试。选定用例还包括既有多层缓存、owner 锁、CDC 批次确认、投影和请求隔离回归。

## 本轮已完成的验证

2026-11-05 在独立 Compose 项目 citymate-cache-p2 中执行：

- Checkstyle、Java 8 编译和选定的 19 项测试全部通过，0 失败、0 错误、0 跳过，见 target/phase2-cache-integration.log 与 Surefire 报告。
- 新增恢复场景 3 项、既有 CacheLayersIntegrationTest 7 项、缓存/CDC/请求隔离单元回归 9 项。
- 同一事件失效失败后保持 PENDING，重试进入 DONE，Inbox 从 0 到 1，后续查询读取新值；事务回滚不发布事件；旧加载被版本栅栏阻止回填。见 [执行轨迹](phase2-cache-recovery-evidence.json)。
- 测试只注入指定失效操作异常；不代表真实 Redis 宕机、Broker/CDC 故障、多实例或进程崩溃验证。重试时间由测试 SQL 推进，不是恢复 SLA。
- 已修正测试夹具必填字段、测试缓存 Bean 名称一致性，以及不能重置版本号复用旧 Memcached 命名空间的问题。
- 脚本兼容 Windows PowerShell 对 Docker stderr 的处理，以进程退出码判断结果；缺失 Maven 依赖允许联网补齐。
- 测试环境已清理，未修改业务数据库和生产实现，未新增性能指标。
- [汇总验证状态](phase2-cache-recovery-verification.json)。

下一阶段：验证秒杀资格、库存预占与数据库库存正确性，再推进可靠交接和补偿。真实 Redis 故障、多实例失效、进程终止和负载实验保留为后续验收项。

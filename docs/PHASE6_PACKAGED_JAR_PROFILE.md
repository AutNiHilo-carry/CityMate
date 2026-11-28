# Phase 6F：独立可执行 JAR 的交易复测

2026-11-08。运行编号 `b383f4f8993b457e8723615136d34ac9`。本轮只新增打包/复测工具并扩展负载器参数，没有再改业务 Java、Lua 或持久化策略。

## 测的是什么

构建当前工作区的 Spring Boot 可执行 JAR，复制进专用镜像；应用容器实际命令为 `java -Xmx512m -jar /app/app.jar`。运行时 bind mount 数量为 0，不挂载源码、target/classes 或测试探针，没有 Maven 父进程或 SpringBootTest 应用 JVM。

基础镜像复用缓存的 Maven/Temurin 8 镜像以保持 Java 版本一致；虽然镜像内带构建工具，应用进程只运行 JAR。本轮不把它宣传为最小化生产镜像或多机部署。

核验内容：Java 8 类文件版本 52；9 个 Lua 文件与源码逐字节一致；迁移、Mapper 和配置资源存在；测试请求探针与 .env 未进入 JAR。JAR SHA-256 为 `0373c567a0261b53979e00746087b6f8a453c7d5a95048f24b30b95baacd2040`，镜像 ID、实际 JVM 版本与命令在 runtime.json 中留存。

## 正常交易结果

独立 Windows Python 负载进程直连发布到回环地址的 18087 端口；4 并发/20 请求预热不计入下表。每档 200 个不同用户请求、100 份免费秒杀库存，100 次有效受理、100 次库存不足，正式履约均为 100 单；系统错误、限流和重复订单为 0。

| 并发 | 全部 HTTP 响应/秒 | 全部响应 P95 ms | 全部响应 P99 ms | 最终订单 |
| --- | --- | --- | --- | --- |
| 8 | 73.50 | 126.18 | 129.47 | 100 |
| 16 | 139.69 | 135.60 | 141.24 | 100 |
| 32 | 264.60 | 137.79 | 147.67 | 100 |

合计 600 次测量请求、300 单；另有 20 单预热。全部对账检查通过：受理与落库订单 ID 集合相等，持久预约 SUCCESS 与 fulfillment Inbox 数量正确，数据库和 Redis 剩余库存均为 0，履约 Outbox 已完成，Stream pending 为 0。

**指标边界**：响应速率和 P99 包含库存不足响应，不是有效订单 TPS。每档只有一个有限批次，没有寻找容量极限。完成观察从准备发送请求到外部确认最终订单，包含文件交接、docker exec 查询和轮询，不能当成消费者稳态吞吐。

## 外部准备与对账

外部 Python 进程在隔离 MySQL 中创建合成券，并向隔离 Redis 写入测试登录状态；随后通过已有受保护的 `/ops/seckill/{id}/publish` HTTP 接口发布活动。它没有 Spring 容器，也不调用 Java 履约服务或逐条代替消费者。

真实处理路径仍是：HTTP 鉴权/限流 → Lua 资格和库存预占 → Redis Stream → 持久预约/Outbox → 真实 RocketMQ → Inbox/正式订单。Redis 的实际 CONFIG GET 已核对 AOF yes / appendfsync always。免费券只验证领取与履约，不是实际收款测试。

采集 17 个 Prometheus/Docker 资源观测点，采集错误 0 个。采样本身会增加负载；首次尝试曾把 redis-cli INFO 的文本当作 JSON，在测量负载开始前退出并清理，修复后添加两项解析回归检查，失败轮次不计入数据。

## 可以得出的结论

当前优化代码能够以独立可执行 JAR 正常启动并完成真实消息履约，资源打包和普通交易对账已验证。之前的源码挂载/测试 JVM 不是唯一能运行的方式。

本次只测优化后的 JAR，没有未优化 JAR 的同条件对照；因此不能把此前开发挂载环境的约 12 倍响应速率改善或 P99 降幅，直接推广为生产收益。本轮与以前运行还存在日期、探针和协调方式差异，不能按数字相减得出因果结论。

仍未完成最大容量、持续长压、多机、真实支付及真实进程故障验收。本轮未经过 Nginx；运行于本机 Docker，也不是线上生产环境。

## 复验与证据

```powershell
python scripts/test-packaged-profile.py
.\scripts\measure-packaged-transaction.ps1
```

脚本仅使用 citymate-jar-p6 专用项目与 18087 端口，拒绝已有同名环境和端口占用。应用镜像只复制已验证 JAR。默认结束后清理专用容器、卷和唯一运行标签的镜像，保留 target 内的构建产物与日志。不要向其填入业务数据库地址。

- [验证汇总](phase6-packaged-profile-verification.json)
- [交易样本与对账](packaged-profiles/b383f4f8993b457e8723615136d34ac9/application.json)
- [资源观测](packaged-profiles/b383f4f8993b457e8723615136d34ac9/observations.json)
- [JAR 与资源核验](packaged-profiles/b383f4f8993b457e8723615136d34ac9/artifact.json)
- [实际运行方式](packaged-profiles/b383f4f8993b457e8723615136d34ac9/runtime.json)

已有主基线文件未覆盖，业务数据库与运行容器未更新，代码未 push。下一步是专用环境中的真实进程终止、重启恢复与同一订单的幂等对账；暂不扩展 Agent 功能。

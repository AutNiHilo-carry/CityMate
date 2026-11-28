# CityMate 本地启动与业务基线验收（2026-10-30）

本次在 Windows 桌面项目 `<CITYMATE_PROJECT_DIR>` 实际构建并验证。未接入 Agent、RAG 或真实支付，未重新压测。历史性能 JSON 未修改。

## 环境与修复

- 本机 JDK 21.0.6；使用 IntelliJ 附带的 Maven 3.9.9 构建，输出 Java 8 字节码。容器实际运行 Temurin 8u504。
- Docker Desktop 4.89.0、Engine 29.7.2、Compose v5.5.0、WSL2 Linux 引擎。
- Docker 启动失败源于不可访问的遗留 socket。保留旧 socket 目录备份后重建临时目录，未恢复出厂设置，未删除镜像或数据卷。
- 从已有 `target/hmdp-cloud-release.tar.gz` 的 `hmdp-cloud/images/` 恢复 57 个文件到同级 `nginx-1.18.0/html/hmdp/imgs`，没有生成图片或覆盖冲突文件。图片仍位于 Java 仓库之外，迁移项目时需要一起保留。
- 修复 `MapperArchitectureTest` 对 Windows 路径分隔符的处理。旧依赖在 JDK 21 下运行本次单元测试时需要 `--add-opens=java.base/java.util=ALL-UNNAMED`；容器 JDK 8 不需要该参数。

## 已执行验证

| 检查 | 本次结果 |
| --- | --- |
| Maven package（跳过业务测试，保留 Checkstyle） | 通过 |
| 13 个套件、30 项现有后端单元测试 | 全部通过 |
| 前端 JavaScript 语法检查 | 通过 |
| 12 项前端测试 | 全部通过 |
| 22 项真实 HTTP 业务检查 | 全部通过，见 [原始结果](citymate-baseline-http.json) |
| 浏览器页面 | 首页分类、笔记、好店加载成功；搜索“火锅”返回两家店铺 |
| 组件状态 | MySQL、Redis、Kafka、RocketMQ 健康；应用报告 CDC 已连接、MQ 客户端已启动、Kafka 消费者正在运行 |

HTTP 检查覆盖：Nginx 首页、恢复图片、健康检查、商户组合搜索和附近查询、查券、匿名订单拒绝、真实验证码登录、普通券重复领取返回原订单、免费券已持有、付费券仍待付款、本人订单列表、跨账号订单拒绝、秒杀受理后 SUCCESS、正式订单存在、重复秒杀返回原订单、售罄拒绝第二账号、跨账号请求拒绝、库存对账和订单任务进度。

这些是功能验收，售罄检查为顺序请求；不能用它宣称完成并发容量或所有故障场景验证。未运行完整隔离集成回归，也未执行付款、退款、核销或多机容灾。

## 当前入口与再次启动

- 网页：<http://127.0.0.1:8080/>
- 体验页：<http://127.0.0.1:8080/experience.html>
- Java 健康检查：<http://127.0.0.1:8081/actuator/health>

本次为了直接运行已通过构建的 JAR，使用可选的 prebuilt 覆盖配置。它仍复用完整开发环境，不启动第二套数据库。重新构建后，在项目根目录 PowerShell 中执行：

```powershell
$env:HMDP_ADMIN_IDS = '1010' # 仅本次本地演示管理员；不要照搬到共享环境
docker compose -p citymate -f dev/docker-compose.yml -f dev/docker-compose.prebuilt.yml up -d
docker compose -p citymate -f dev/docker-compose.yml logs --tail 50 java
```

去掉 prebuilt 覆盖文件即可回到原来的容器 Maven 启动方式。首次启动需要等待 Java 初始化；容器 running 不等于健康接口已可用。

本次新增的用户 ID 为 1010、1011；三张标记为演示的券为 10、11、12，订单号见 JSON。数据保留以便检查，其中付费券只创建待付款单，之后可能被超时关闭。秒杀演示活动有截止时间，不能长期用同一张券反复验收。

本次本地自动生成的演示管理员手机号为 `13967394900`，验证码仍从 Java 调试日志读取，不发送真实短信。复验脚本支持复用已有演示管理员，每次另外创建新的演示券：

```powershell
python scripts/verify-local.py --admin-phone 13967394900 --output docs/citymate-baseline-http-repeat.json
```

脚本会写入本地演示账号、优惠券和订单，不用于生产环境；不保存令牌、验证码或付款信息。未配置演示管理员时，查询与登录可能成功，但创建券的步骤会被权限检查拒绝。

## 证据与日志

- [HTTP 业务验收](citymate-baseline-http.json)：对象 ID、逐项检查结果和生成时间。
- `target/citymate-baseline-build.log`：本机 Maven 构建。
- `target/citymate-baseline-unit.log`：最终通过的后端测试。
- `target/citymate-baseline-compose.log`：镜像拉取及首次容器启动。
- `target/citymate-baseline-http.log`：本次 HTTP 验收输出。

`target` 下日志是本地生成物，不作为新测性能指标。已有工作区修改全部保留。

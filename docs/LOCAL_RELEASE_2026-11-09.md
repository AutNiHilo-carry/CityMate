# 审计修复应用到本地运行环境

本页是较早的审计修复发布记录。后续同日已更新为 [Phase 7 单 Agent 只读查询版](PHASE7_READ_ONLY_AGENT.md)，当前包与部署证据以该阶段为准。

日期：2026-11-09。运行 ID：`b8bc543411634b2fa3db346dda1766d3`。新 JAR 已替换本地预构建包，Java 容器已重建并健康运行，网页和 API 可访问。

## 实际完成

- 在独立临时构建容器内运行 Java 8 `mvn -DskipTests package`，Checkstyle 和打包通过；没有在本轮重复执行此前已通过的 174 项后端和 18 项前端测试。
- 核对审计记录的 8 份源文件哈希，核验 JAR 中 **176 个应用类的版本均为 52**、**35 份正式资源**及其中 9 份 Lua 与源码逐字节一致。没有 .env 或测试探针；数据库迁移文件与旧 JAR 相同。
- 旧 JAR 保留于 `target/local-releases/b8bc543411634b2fa3db346dda1766d3/previous.jar`，新包先验证后安装。
- 恢复原有 MySQL、Redis、Memcached、RocketMQ、Kafka、Canal 等容器；只重建 Java 容器，随后启动原 Web 容器。MySQL/Redis 容器 ID 和原命名数据卷保留，没有清库或初始化新业务库。
- 使用已有脚本与本机 .env 更新 Java，未读取或打印 API Key。服务恢复后，正常的后台任务与访问统计可以继续处理既有工作；这不意味着数据库内容在启动前后逐字节不变。

新包 SHA-256（宿主机、部署记录与 Java 容器内观察一致）：

```text
198c88b515675481053c5bda4a6bde7c50d1fd9ba8d6024118e8f02ed6e46c06
```

当前使用原开发 Maven 镜像的入口脚本启动 `java -jar target/citymate-0.0.1-SNAPSHOT.jar`，不通过 `mvn spring-boot:run` 启动应用。临时构建容器与其专用网络/卷已清理；业务服务保持运行。

## HTTP 验收

17 项检查通过：Java health、商户分类与列表、首页/店铺列表/体验页/助手页路由、JavaScript、Nginx API 转发和能力查询；未登录访问本人订单、带分号管理路径、监控路径、商户写入与 AI 问答均被拦截；旧分页溢出参数被拒绝。

本轮仅进行了公开查询和未登录拦截检查，没有创建用户、领取券、发起支付或调用真实模型。没有进行浏览器布局/键盘验收，也没有以匿名拦截替代此前审计中的普通用户/管理员权限回归。

- [首页](http://127.0.0.1:8080/)
- [体验验证](http://127.0.0.1:8080/experience.html)
- [助手页面](http://127.0.0.1:8080/assistant.html)
- [机器汇总](local-release-verification.json)
- [资源核验](local-releases/b8bc543411634b2fa3db346dda1766d3/artifact.json)
- [部署记录](local-releases/b8bc543411634b2fa3db346dda1766d3/deployment.json)
- [17 项 HTTP 结果](local-releases/b8bc543411634b2fa3db346dda1766d3/smoke.json)
- [本次所应用的审计修复](LOGIC_AUDIT_2026-11-09.md)

## 下次怎样更新

在 Docker Desktop 运行、当前项目数据容器仍存在、根目录 .env 已配置的前提下，从项目根目录执行：

```powershell
.\scripts\apply-local-audit-fixes.ps1
```

脚本校验项目标签与源码挂载目录，保留旧 JAR 并验证新包，再恢复依赖、替换并重建 Java。它针对现有本机环境，不是异机首次部署脚本；遇到不匹配环境会停止。没有配置自动数据回滚；若安装后启动失败，旧 JAR 与构建日志留在本轮 target/local-releases 下供核对。

本轮未 push。后续开始单 Agent 的只读工具调用循环，复用现有商户/券/本人订单工具和 BM25 RAG；领取确认与执行另外分阶段实现。

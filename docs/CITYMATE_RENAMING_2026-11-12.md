# CityMate 工程命名统一

日期：2026-11-12。

## 当前入口

工程目录：`<CITYMATE_PROJECT_DIR>`。

请在 VS Code 使用“文件 → 打开文件夹”重新选择该目录。旧窗口中的标签页、终端工作目录和 Java 工程缓存不会随文件系统改名自动更新。

打开 Docker Desktop 后，在 PowerShell 执行：

```powershell
cd <CITYMATE_PROJECT_DIR>
docker compose -p citymate -f dev/docker-compose.yml -f dev/docker-compose.prebuilt.yml up -d
```

首页：<http://127.0.0.1:8080/>。

查看服务及日志：

```powershell
docker compose -p citymate -f dev/docker-compose.yml ps -a
docker compose -p citymate -f dev/docker-compose.yml logs --tail 80 java
```

## 修改范围

- 工程目录、IDE 模块文件及引用。
- Maven artifactId / name：`citymate`；产物：`target/citymate-0.0.1-SNAPSHOT.jar`。
- Java 启动类 `CityMateApplication`，对应测试类和脚本中的归档入口。
- Compose 项目、容器、网络、数据卷的新名称，以及启动、构建、验收和发布脚本。
- README、Wiki 和说明文档中的当前使用命令及路径。

`com.hmdp` Java 包、`hmdp` 数据库、表、Redis 键、消息 Topic 及已有环境变量沿用原兼容标识，未做无关的数据结构迁移。课程来源说明保留。

历史验收原始 JSON、日志、构建备份不改写；它们记录的是当时真实环境，不作为当前启动说明。

## 数据迁移与验证

原项目处于停止状态时，将 6 个数据卷复制到新项目前缀：MySQL、Redis、Kafka、RocketMQ、Canal 元数据及 Maven 缓存。逐卷比较文件内容树 SHA-256 后再启动新容器。复制过程保留文件属性，原卷没有删除，作为迁移备份保留。

原卷备份不能自动跟随新运行数据更新；恢复应另行规划，不能直接切换到过时副本。没有改变上传图片的原始挂载目录。

- Java 8 目标字节码构建、Checkstyle 和 Spring Boot 打包通过；JAR 的 Start-Class 为 `com.hmdp.CityMateApplication`。
- 改名后的隔离后端回归 205 项通过，0 失败 / 错误 / 跳过，运行编号 `cf9a19120ecc4c7cab2586e2903ac19e`。
- 前端 74 项通过，语法及模板编译通过，0 模板警告。
- 本地商户只读检查 14 项、优惠券检查 19 项通过；故障浏览器交互 11 项及找店 / 订单交互 9 项通过，写请求使用夹具。
- 新服务健康 UP；数据库版本检查正常；主页及业务接口能返回既有数据。
- `.env` 随目录保留且仍被 Git 忽略，没有将 Key 写入代码或报告。

本机迁移与源码备份在 `target/citymate-storage-migration.json`、`target/citymate-rename-source-backup/`。没有提交或推送 Git，没有新增压测或模型质量结论。

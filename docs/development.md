# 正式工程开发与运行

## 模块边界

采用单 Maven 应用、按 Java 包划分模块的最小模块化单体。暂不引入多 Maven 模块或无调用者接口；当前边界靠包职责与代码审查约束，尚无自动依赖规则。

| 包 | 职责 |
| --- | --- |
| `io.iwork.identity` | 身份与资源：认证、授权、资源归属。当前安全配置仅放行静态入口及两个无详情健康端点，其他请求默认拒绝。后续账号票替换相应授权规则。 |
| `io.iwork.conversation` | 会话与任务：持久业务状态、幂等、执行互斥、取消和重启中断。 |
| `io.iwork.agent` | 智能体运行：AgentScope 推理和受控工具调用，不替代 iWork 业务权威。仅固定 core 依赖，不加载模型、工具或沙箱。 |
| `io.iwork.artifacts` | 成果与文件：原始资料、执行工作区、正式版本及文件索引。当前只验证存储目录可写。 |
| `io.iwork.model` | 模型与用量：模型授权、额度、逐调用计量；供应商缺失用量保留待核实。 |

各模块通过后续真实用例需要的包接口协作，不直接访问其他模块内部持久化对象。没有业务表、CRUD 占位、通用接口包装或伪业务响应。沙箱管理服务和执行看门狗在 #16 接入；其高权限信任边界以及有条件的进程停止上限保持不变。

## 构建与迁移

Spring Boot 4.0.9 支持 Java 21（[官方兼容要求](https://docs.spring.io/spring-boot/4.0/system-requirements.html)）。AgentScope core 固定为已有探针验证过的 2.0.4，正式入口只验证依赖可构建和装载，不代表真实模型集成已验收。

Boot 4 使用 Web MVC 与 Flyway 专用 starter，健康检查和安全自动配置类使用新包名。Web 层采用 Boot 管理的 Jackson 3，AgentScope 保留 Jackson 2；`AgentScopeCompatibilityTest` 验证 SDK 消息在正式应用依赖组合下可序列化并读回。安全版本覆盖集中在后端 POM，MCP 使用 BOM 统一模块版本。升级后在 IDEA 重新加载 Maven 项目，再刷新安全检查。

2026-11-16 后端依赖安全修复：

| 原告警构件 | 处理 |
| --- | --- |
| MySQL Connector/J 9.7.0 | 升至 26.10.0；[官方说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-26-10-0.html)支持 MySQL 8.4 及以后版本，数据库服务版本保持 8.4.7。 |
| Logback 1.5.38 | 升至 1.6.7；[官方说明](https://logback.qos.ch/news.html)将 1.6 系列作为 1.5 的替代版本，本项目未使用 Janino 条件配置。 |
| Jackson 3.1.7 | BOM 升至 3.1.8；[修复说明](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.8)包含 CVE-2026-110879。Jackson 2 保持 2.21.8。 |
| MCP core 0.17.2 | MCP BOM 升至 1.1.5，包含 DNS rebinding、CORS 和[请求体无界读取](https://github.com/modelcontextprotocol/java-sdk/security/advisories/GHSA-4x8c-5vv7-973f)修复；新增 `McpCompatibilityTest` 验证 AgentScope 同步、异步 Streamable HTTP 初始化、工具发现及工具调用。 |
| json-smart 2.6.0 | 升至 2.6.1。 |
| ASM 9.7.1 | 随 json-smart/accessors-smart 2.6.1 升至 9.11.1，无单独版本覆盖。 |
| android-json | 排除当前测试未使用的 JSONAssert，从测试依赖树移除该无维护版本；现有断言使用 AssertJ。 |

7 项后端测试、IDEA 构建和隔离 Compose 验收通过（启动、迁移、重建、文件保留及数据库中断恢复）；IDEA/Mend 后端 POM 检查从 7 条降为 0 条，OSV 对最终 152 个依赖构件未检出已知漏洞。这是指定日期和依赖树的扫描结果，不是无漏洞保证。MCP 验证使用本机合成服务，未覆盖外部服务、SSE/stdio、认证或 MCP 服务端。两个历史原型仍保留各自的依赖组合；这里的零告警结论仅针对正式后端。

Flyway 随应用启动运行，`backend/src/main/resources/db/migration/V1__baseline.sql` 仅执行无副作用语句并登记基线。历史存储在 MySQL `flyway_schema_history`，不创建业务表。再次启动验证校验和；迁移失败会阻止主应用启动，不提供忽略失败、自动 clean 或对非空数据库自动 baseline 的入口。后续业务票新增 V2、V3 等迁移，不修改已经应用的文件。

`-Pdistribution` 依赖先执行前端构建；开发时只检查 Java 可以不用该 profile。Docker 多阶段构建始终重新构建前端，不将本机 `node_modules`、`target`、`.env` 或数据目录放入构建上下文。最终镜像仅有运行时和 jar。

## 外部配置

| 环境变量 | 用途/默认 |
| --- | --- |
| `IWORK_DB_HOST` / `IWORK_DB_PORT` | 本机 Java 默认为 localhost:3306；Compose 内使用 mysql:3306。 |
| `IWORK_DB_NAME` / `IWORK_DB_USER` | 本机 Java 默认 iwork；Compose 初始化同名库和账号。 |
| `IWORK_DB_PASSWORD` | 必填，无真实凭据或开发默认密码。Compose 对空值立即报错；Java 启动需通过真实数据库认证和迁移。 |
| `IWORK_MYSQL_ROOT_PASSWORD` | Compose 初始化 MySQL 必填，只传给 MySQL，不传给主应用。 |
| `IWORK_FILES_DIRECTORY` | Java 文件路径，默认工作目录下 `data/files`；Compose 固定容器内 `/data/files`。启动验证可创建/删除文件。 |
| `IWORK_DATA_DIR` | Compose 宿主持久根目录，默认仓库 `data`。 |
| `IWORK_HTTP_PORT` | Compose 宿主回环端口，默认 8080。 |

`.env.example` 是空凭据模板，`.env` 只由 Compose 读取，本机 Java 不自动加载它。仅使用合成测试凭据；前端不接受密钥配置，不将任何服务端配置送给浏览器。不要把真实凭据加入 Vite 环境变量。日志默认 INFO，容器日志每个服务最多 3 × 10 MB；HTTP 错误不返回异常、栈或配置。健康检查不公开组件/详情；不要在共享环境开启 DEBUG/TRACE 或输出 `docker compose config`（其中可能展开密码）。

## 本机开发

后端可连接你自行准备的独立 MySQL 8.4.7 测试库；不要复用其他项目账号或数据库。准备好 `iwork` 库与账号后：

```sh
export IWORK_DB_PASSWORD='你的本地测试密码'
export IWORK_FILES_DIRECTORY="$PWD/data/files"
backend/mvnw -f backend/pom.xml spring-boot:run
```

另一终端运行 `npm --prefix frontend run dev`，Vite 将健康请求转发至 127.0.0.1:8080。正式浏览器验证使用 Compose 的已打包入口。

## 基础排错

- `docker compose ps` 检查服务状态；`docker compose logs --tail=100 app mysql` 查看启动或迁移失败。不要复制包含私密数据的日志到公开位置。
- `/actuator/health/liveness` 仅表明进程存活；`/actuator/health/readiness` 同时检查数据库和文件目录，失败返回 503。数据库启动失败时主应用不启动；运行中数据库失联不触发进程重启，待数据库恢复后可恢复就绪。
- 文件目录不可写时检查宿主目录和 UID/GID 10001；初始化 `chown` 命令需临时添加 `CHOWN` capability，单用 root 仍会被应用的 `cap_drop: ALL` 限制。不要让主应用永久以 root 运行或保留该 capability。
- 数据库认证失败时检查实际账号密码与环境是否一致；连接超时需检查网络和 MySQL 状态。连接建立/读写超时 3 秒，连接池获取超时 3 秒；健康探测还有调度与连接池开销，不把这些配置当作完整响应 SLA。
- Flyway 校验失败时还原误改的旧迁移并新增迁移，不删除历史表或已有数据。
- macOS Testcontainers 找不到 Docker 时，确认 Docker 引擎已启动；必要时将 `DOCKER_HOST` 指向 `docker context inspect` 显示的当前 socket，勿挂载到主应用。

## 最小浏览器验收

启动 Compose 后打开首页，检查“服务已就绪”。按 Tab 定位“检查连接”，验证焦点可见且 Enter 可以重试。停止该实例 MySQL，再点击按钮应显示“服务暂不可用，请稍后重试”，页面不得显示主机、数据库、异常或凭据。恢复 MySQL 后重试应回到就绪。检查窄屏无横向滚动。当前尚无登录、会话或成果页面。

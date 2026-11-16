# 正式工程开发与运行

## 模块边界

采用单 Maven 应用、按 Java 包划分模块的最小模块化单体。暂不引入多 Maven 模块或无调用者接口；当前边界靠包职责与代码审查约束，尚无自动依赖规则。

| 包 | 职责 |
| --- | --- |
| `io.iwork.identity` | 身份与资源：用户名密码、Redis Session、管理员用户管理、实时 authVersion 撤权及部署密码恢复。 |
| `io.iwork.conversation` | 会话与任务：持久业务状态、幂等、执行互斥、取消和重启中断。 |
| `io.iwork.agent` | 智能体运行：AgentScope 推理和受控工具调用，不替代 iWork 业务权威。仅固定 core 依赖，不加载模型、工具或沙箱。 |
| `io.iwork.artifacts` | 成果与文件：原始资料、执行工作区、正式版本及文件索引。当前只验证存储目录可写。 |
| `io.iwork.model` | 模型与用量：模型授权、额度、逐调用计量；供应商缺失用量保留待核实。 |

各模块通过真实用例需要的包接口协作，不直接访问其他模块内部持久化对象。V2 新增 users 与 conversations；会话写入调用身份模块的事务内校验，SQL 始终限定 owner。沙箱管理服务和执行看门狗在 #16 接入；其高权限信任边界以及有条件的进程停止上限保持不变。

## Harness 目标方案与当前实现

首版采用 HarnessAgent，边界见 [ADR 0004](adr/0004-harness-runtime-and-context.md)。当前后端仍只引入 core；#14 接入 Harness 并验证依赖组合、工具范围和统一模型调用治理，新增上下文任务交付恢复与自动压缩。不要把下面已有工程测试、依赖扫描记录解释为 Harness 已通过。

完整业务消息、运行上下文快照和会话摘要统一保存在 MySQL；SDK 快照不是业务状态权威，不携带可恢复的历史授权，不自动续跑任务。关闭不需要的 SDK 工具、长期记忆及独立会话日志；主推理与压缩统一授权、计量和取消。原始资料、工作文件及成果仍独立保存，具体实现与 T17–T19 验收由开发票承接。

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

Flyway 随应用启动运行，V1 保留原基线，V2 新增账号与个人会话表。历史存储在 MySQL `flyway_schema_history`。再次启动验证校验和；迁移失败会阻止主应用启动，不提供忽略失败、自动 clean 或对非空数据库自动 baseline 的入口。后续业务票继续新增迁移，不修改已经应用的文件。

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

推荐使用根目录的统一 npm 入口，完整操作见 [README 本地开发](../README.md#本地开发推荐)：

```sh
npm run setup
npm run dev
```

根目录 `package.json` 不管理前端依赖，前端仍由 `frontend/package-lock.json` 和 `npm ci --prefix frontend` 管理；后端仍由 Maven Wrapper 管理。开发脚本只负责配置加载、Compose 依赖启动、端口检查、前台日志和进程清理，不改变交付构建。

`compose.dev.yaml` 使用独立项目名和 MySQL/Redis 命名卷，依赖端口仅绑定回环地址。开发配置 `.env.development` 的数据库密码与交付 `.env` 分离，文件目录为 `data/dev/files`。脚本只允许本地依赖地址，防止开发迁移误连远程库；不要手动将 IDEA 指向其他环境。

IDEA 调试时先执行 `npm run dev:infra`，将 `.env.development` 作为后端运行配置的环境文件加载，以仓库根目录为工作目录启动 `IWorkApplication`，前端用 `npm run dev:frontend`。Vite 仍将 `/api` 与健康请求转发至 `127.0.0.1:8080`。Java 修改后需要重新运行，未接入 DevTools。独立执行 Maven 命令时也需要自行导出配置；`.env.development` 不由 Spring Boot 自动加载。

开发进程前台运行，Ctrl+C 清理本次应用进程，Compose 依赖保留；`npm run dev:down` 停止依赖且保留数据。脚本没有清库或重置密码入口。修改依赖端口后再次运行 `dev:infra`；修改数据库密码必须同步修改数据库账号，不能仅修改配置文件。

## 基础排错

- `docker compose ps` 检查服务状态；`docker compose logs --tail=100 app mysql` 查看启动或迁移失败。不要复制包含私密数据的日志到公开位置。
- `/actuator/health/liveness` 仅表明进程存活；`/actuator/health/readiness` 同时检查数据库、Redis 和文件目录，失败返回 503。数据库启动失败时主应用不启动；运行中依赖失联不触发进程重启，恢复后可恢复就绪。
- 文件目录不可写时检查宿主目录和 UID/GID 10001；初始化 `chown` 命令需临时添加 `CHOWN` capability，单用 root 仍会被应用的 `cap_drop: ALL` 限制。不要让主应用永久以 root 运行或保留该 capability。
- 数据库认证失败时检查实际账号密码与环境是否一致；连接超时需检查网络和 MySQL 状态。连接建立/读写超时 3 秒，连接池获取超时 3 秒；健康探测还有调度与连接池开销，不把这些配置当作完整响应 SLA。
- Flyway 校验失败时还原误改的旧迁移并新增迁移，不删除历史表或已有数据。
- macOS Testcontainers 找不到 Docker 时，确认 Docker 引擎已启动；必要时将 `DOCKER_HOST` 指向 `docker context inspect` 显示的当前 socket，勿挂载到主应用。

## #11 账号与登录态

正式工程已增加 users/conversations 的 V2 迁移，V1 保持不变。identity 提供可信 Caller 与事务内身份锁定校验，conversation 所有查询限定 owner。仅支持会话创建、分页列表、空详情；消息、任务及模型未接入。

在独立 MySQL 之外准备 Redis，默认 localhost:6379，可用 `IWORK_REDIS_HOST` / `IWORK_REDIS_PORT` 修改。首次启动必须提供 `IWORK_BOOTSTRAP_PASSWORD`（至少 12 字符，最多 72 UTF-8 字节），可用 `IWORK_BOOTSTRAP_USERNAME` 设置管理员用户名（默认 admin）。只有数据库不存在 ADMIN 才初始化；正常启动和改动配置都不会覆盖现有账号。首次改密完成后移除密码配置。用户名为 3–64 位 ASCII 字母、数字、点、下划线或连字符，以字母/数字开头，不区分大小写；显示名称支持中文。

生产默认 `IWORK_COOKIE_SECURE=true`，必须通过 HTTPS 使用。仅本机 HTTP 开发显式设置 `IWORK_COOKIE_SECURE=false`。Redis 只保存 Spring Session，Compose 使用内部网络/AOF命名卷，无宿主端口；MySQL 用户与会话不存 Redis。Cookie HttpOnly/SameSite=Lax，最长 30 天；服务端另校验自登录起 30 天绝对期限及 7 天闲置期限。多设备可同时登录，改密/重置/禁用原子递增 authVersion 撤销所有旧登录；重新启用不会复活旧登录。

登录和所有写请求须先 GET `/api/auth/csrf`，将 token 放在 `X-CSRF-TOKEN` 请求头；登录为 form POST，其他写请求为 JSON。登录更换 Session ID；自行改密拒绝保留原密码，成功返回 204 并撤销当前及其他设备登录，用户须用新密码显式重新登录。下次写入应获取新 CSRF。临时密码只能访问本人、改密、退出及 CSRF 接口。错误 401/403/404/409/503 分别表示身份、权限/CSRF、资源不存在（含非本人）、重复用户名、依赖不可用，不回传内部异常。会话和用户分页参数 page 从 0 开始、size 默认 20，最大 100。

Redis 正常重建保留登录；删除 Redis 命名卷只丢失登录，重新登录后业务记录仍在 MySQL。不兼容 Session 序列化升级可以使旧登录失效，应在升级说明中要求重新登录；不能把 Redis 当作业务备份。AOF everysec 不保证断电零丢失。

Vite 同时代理 `/api` 与健康请求。readiness 检查 MySQL、Redis 和文件目录；公开静态入口及探针不加载登录 Cookie，失效登录不影响页面刷新或 liveness。生产需配置 HTTPS 反向代理，主应用端口仍仅绑定回环。

## 部署管理员密码恢复

交互方式（隐藏输入，不在命令参数/日志传递密码）：

```sh
docker compose run --rm --no-deps app --recover-admin
```

需要真正的交互终端。非交互部署可把新临时密码放入仅执行用户可读的普通文件（0600/0400，禁止符号链接和组/其他用户权限），通过 `IWORK_RECOVERY_PASSWORD_FILE` 指定文件路径。容器执行用户 UID 为 10001，挂载文件须让该用户可读；密码不要写在命令行、脚本正文或 Git 中。例：

```sh
docker compose run --rm --no-deps \
  -v /secure/iwork-recovery-password:/run/recovery-password:ro \
  -e IWORK_RECOVERY_PASSWORD_FILE=/run/recovery-password app --recover-admin
```

该命令无 HTTP 监听，复用相同后端密码校验、BCrypt 与数据库事务；重置配置用户名对应的管理员、清除锁定、撤销所有旧登录，下一次登录强制改密。完成后安全删除临时文件。恢复只需数据库可用；不修改业务会话和角色。参数中不能跟密码。

## #11 浏览器与回归验收

使用独立 Compose 实例，以初始管理员登录→首次改密→创建普通用户并记录一次性密码→退出→普通用户首改→新建会话并刷新/打开直接链接→管理员重置/禁用→旧登录失效。检查键盘 Tab/Enter、可见焦点、窄屏、加载失败重试。模型、消息和任务未在 #11 实现。

验证命令仍为 README/CI 的 npm、Maven、Compose 命令；证据与未验证范围见 [#11 验证记录](validation/issue-11.md)。

## #12 模型配置与个人额度

已增加 V3 模型、授权和预算配置；管理员通过“模型管理”保存模型、服务端凭据引用、输入/输出计价、用户授权和累计预算，用户通过“模型与额度”查看本人可用模型及余额。详细字段、币种锁定与外部凭据注入见 [#12 实施合同](issue-12-design.md)。本票只保存配置，不证明真实模型可连接或已经接入扣费；Harness 执行与计量仍由 #14 承接。

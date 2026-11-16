# 正式工程开发与运行

## 模块边界

采用单 Maven 应用、按 Java 包划分模块的最小模块化单体。暂不引入多 Maven 模块或无调用者接口；当前边界靠包职责与代码审查约束，尚无自动依赖规则。

| 包 | 职责 |
| --- | --- |
| `io.iwork.identity` | 身份与资源：用户名密码、Redis Session、管理员用户管理、实时 authVersion 撤权及部署密码恢复。 |
| `io.iwork.conversation` | 会话与任务：持久业务状态、幂等、执行互斥、取消和重启中断。 |
| `io.iwork.agent` | 智能体运行：AgentScope 推理和受控工具调用，不替代 iWork 业务权威。Harness 2.0.4 通过统一授权/计量入口执行文字推理；默认通用工具关闭。 |
| `io.iwork.artifacts` | 成果与文件：原始资料、执行工作区、正式版本及文件索引。原始资料使用独立存储和所有权索引，解析与正式成果另行接入。 |
| `io.iwork.model` | 模型与用量：模型授权、额度、逐调用计量；供应商缺失用量保留待核实。 |

各模块通过真实用例需要的包接口协作，不直接访问其他模块内部持久化对象。V2 新增 users 与 conversations；会话写入调用身份模块的事务内校验，SQL 始终限定 owner。沙箱管理服务和独立执行看门狗已在 #16 接入；其高权限信任边界以及有条件的进程停止上限保持不变。

## Harness 目标方案与当前实现

首版采用 HarnessAgent，边界见 [ADR 0004](adr/0004-harness-runtime-and-context.md)。当前引入同版本 core、harness 与 extensions-model-openai；默认通用工具、长期记忆及子智能体关闭。2.0.4 仍注册 wait_async_results，因此构建后显式移除并断言工具集合严格匹配本任务配置（授权资料读取、DOCUMENT 模式正文准备）。主调用及辅助调用经 GovernedModel 逐次授权、预占和记录。#27 通过 MySQL 保存成功任务的文本上下文，并在显式新任务前调用官方 ConversationCompactor；不恢复历史权限或待执行工具。见 [上下文契约](issue27-context-contract.md)与 [#14 计量规则](issue-14-design.md)。真实供应商兼容性仍由 #26 验证。

完整业务消息、运行上下文快照和会话摘要统一保存在 MySQL；SDK 快照不是业务状态权威，不携带可恢复的历史授权，不自动续跑任务。关闭不需要的 SDK 工具、长期记忆及独立会话日志；主推理与压缩统一授权、计量和取消。原始资料、工作文件及成果仍独立保存，具体实现与 T17–T19 验收由开发票承接。

## 构建与迁移

Spring Boot 4.0.9 支持 Java 21（[官方兼容要求](https://docs.spring.io/spring-boot/4.0/system-requirements.html)）。AgentScope core 固定为已有探针验证过的 2.0.4，正式入口还使用可控供应商传输验证 Harness 调用，不代表真实供应商兼容已验收。

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

## #13 后台任务

V4 增加任务、消息与请求身份；会话页面可提交、观察、取消并重开找回，接口和恢复边界见 [#13 实施合同](issue-13-design.md)。未接入 TaskRunner 时正式入口返回 503；不会把测试替身装入交付 JAR。测试替身仅由 `io.iwork.fixtures.TaskFixtureApplication` 在 test classpath 启动，用于浏览器及进程强杀验证。

`iwork.task-capacity` 默认 2，范围 1–16；单实例运行期间保留实际执行许可，取消不提前释放。不要同时启动两个应用实例连接同一业务数据库；此首版不提供多实例调度或滚动升级。主进程启动只归档遗留任务，不重新执行。


## #16 资料解析与隔离服务

部署前运行 `node scripts/sandbox-key.mjs`，在 `data/secrets/sandbox.key` 创建随机服务密钥，已存在时拒绝覆盖。父目录应仅部署者可进入；文件作为只读 Compose secret 仅挂载给 app 与 sandbox-manager，不给看门狗和处理容器。不要输出文件内容、提交 Git 或挂载给其他服务。可通过 `IWORK_SANDBOX_KEY_FILE` 指定已有文件。实例名 `IWORK_SANDBOX_INSTANCE` 必须在同一 Docker 主机唯一，并与持久 registry 保持一致。

`docker compose build` 构建主应用、管理服务和固定 Python 解析镜像；`docker compose up -d` 启动服务。text-parser-image 是构建依赖的短暂版本检查容器，成功退出属于预期。管理接口仅在内部 sandbox-control 网络，不发布宿主端口；只有管理服务及无网络看门狗挂载 Docker socket。它们不启动 Spring、不持有 DB/模型凭据。管理 registry 与看门狗健康记录各有独立命名卷，应保留 registry 做恢复核验。

本机 Java 如需解析，需运行隔离服务并配置 `IWORK_SANDBOX_URL`、`IWORK_SANDBOX_KEY_FILE`；默认开发脚本只启 MySQL/Redis，未配置管理服务时解析明确不可用，文字任务仍可运行。生产不得为方便开发而开放管理端口。测试的回环端口覆盖只用于可丢弃实例。

支持 TXT/Markdown UTF-8 文本，最大 2 MiB；原文保持不变，解析结果是任务材料，按 12,000 字符分段读取。用户在“资料处理”查看状态、清理确认与解析文本。取消或失败不把未确认的容器报告为已清理；管理端持续重试，独立看门狗按 Docker 创建时间执行 60 秒期限。该上限依赖 Docker/宿主可用，生产时间尚待 #25 标定。

验证：`sandbox/verify.py URL KEY_FILE COMPOSE_FILE issue16实例名` 在可丢弃真实 Docker 实例检查身份/任务令牌、解析、隔离、取消竞态、监督失效与到期回收；会暂停该测试实例看门狗。业务 API 集成测试 `MaterialParsingTest` 需 `IWORK_SANDBOX_TEST_URL` 与 `IWORK_SANDBOX_TEST_KEY_FILE`，未配置则显式跳过；真实身份/MySQL/Redis/文件/Harness，仅模型 HTTP 传输为替身。测试替身入口 `ParsingFixtureApplication` 不进入交付 JAR。

## #17 Markdown 正式文档

任务提交可设置 `outputMode: DOCUMENT`，默认/旧请求仍为 `TEXT`。文档任务开放固定 `prepare_document` 工具，先准备正文，再由 TaskService 在任务状态事务内发布成果/版本并完成任务；没有可保存正文则失败，不把模型回复当作正式成果。V8 为成果、版本、准备记录建立独立索引；同任务最多一个正式版本。

首版输出 UTF-8 Markdown，正文上限 256 KiB、标题最多 120 字符。会话“正式文档”提供只读原样预览及 `.md` 附件下载，不执行 HTML/代码。版本读取按 owner 查询并检查字节数/SHA-256；修改后的不完整文件返回 503，不静默返回坏内容。正式目录 `documents` 与 `originals`、`document-workspaces` 分开；TaskService 正常终止与启动中断归档后只清理文档工作区。

保存顺序为完整文件先落盘、数据库事务后登记；取消与发布共用任务行锁。数据库提交结果不明或失败时可能留下完整无引用文件，保留它避免误删已登记版本；只能在停止服务并核对数据库索引后清理。不得直接删除整个 files 根目录。首次版本为 1，继续修改形成新版本由 #18 接入。合同见 [#17 设计](issue-17-design.md)。

## 长会话上下文（#27）

V9 增加 MySQL conversation_contexts 与模型 context_window。升级后已有模型窗口为空，管理员需在模型管理中按供应商确认规格填写 8192–131072；更大模型也只启用首版上限，空值会返回 context_window_not_configured。不要把确定性测试替身的参数复制到真实模型。

业务历史与可用快照参与协调备份，不属于可清理的临时工作目录。损坏快照可按完整成功业务记录重建；无法确认或超限时明确失败。中断任务不自动续跑，只响应新的显式任务。测试入口 ConversationContextTest 与仅用于本地验收的 ContextFixtureApplication，后者不进入正式 JAR。

## 来源版本与续改（#18）

DOCUMENT 任务可传 baseVersionId，提交时校验所属用户并纳入幂等指纹。固定 read_document_version 只能读本任务选定来源，12000字符分页；正式完成时锁成果并分配新版本号，各版本保留标题、正文与来源关系。旧版续改与并发续改均追加，不覆盖旧文件。个人版本列表 `/api/document-versions?page=0` 每页20项；会话文档列表包含本会话引用成果的历史版本。V10 迁移保留既有版本标题。详见 [版本契约](issue18-version-contract.md)。

## 取消与迟到结果验证（#19）

业务取消、本地worker结束、供应商状态与处理容器清理分别表达；STOPPED不代表供应商停止。正常执行结果复用包内 acceptResult，测试远端回执也经过同一终态/正式提交边界。CancellationClosureTest 和 CancellationFixtureApplication 仅在test源集，正式包不含控制入口。真实容器取消/清理未知验证见 sandbox/verify_cancellation.py 与 [验收记录](validation/issue19-cancellation.md)。

## 重启恢复（#20）

业务 API 在启动恢复完成前返回503。依次归档未完成任务、幂等关闭调用与释放预占，再启动容器状态核对；不会自动续跑。原 requestId 仍返回中断任务，显式新请求重新准入。摘要/快照只有成功来源且历史核对通过才恢复。真实强杀与两个活容器进程树回收见 [契约](issue20-recovery-contract.md) 与 [验证](validation/issue20-recovery.md)。

## 清理故障与独立监督（#21）

清理失败保留UNKNOWN并拒绝新增处理，恢复后按持久关联重试。看门狗健康记录超过2秒不接纳新处理，恢复后按Docker原创建时间检查60秒期限；不重置。实际确认删除输出sandbox_expired_removed回执，仅含标识与毫秒时间。部署应采集该日志并保留条件性期限边界；见 [监督契约](issue21-supervision-contract.md)。

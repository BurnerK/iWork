# 项目开发与组件发布

## 本地开发

首次运行见 [README 快速开始](../README.md#本地开发推荐)。日常开发从仓库根目录运行以下命令。

| 命令 | 用途 |
| --- | --- |
| `npm run dev:infra` | 只启动并等待 MySQL / Redis 就绪 |
| `npm run dev:backend` | 只运行后端，依赖需已启动 |
| `npm run dev:frontend` | 只运行前端 |
| `npm run test:dev` | 检查开发脚本的配置保护、端口冲突和进程清理 |

`compose.dev.yaml` 使用独立项目名和 MySQL/Redis 命名卷，依赖端口仅绑定回环地址。开发配置 `.env.development` 的数据库密码与交付 `.env` 分离，文件目录为 `data/dev/files`。脚本只允许本地依赖地址，防止开发迁移误连远程库；不要手动将 IDEA 指向其他环境。

IDEA 调试时先执行 `npm run dev:infra`，将 `.env.development` 作为后端运行配置的环境文件加载，以仓库根目录为工作目录启动 `IWorkApplication`，前端用 `npm run dev:frontend`。Vite 仍将 `/api` 与健康请求转发至 `127.0.0.1:8080`。Java 修改后需要重新运行，未接入 DevTools。独立执行 Maven 命令时也需要自行导出配置；`.env.development` 不由 Spring Boot 自动加载。

修改依赖端口后再次运行 `dev:infra`；修改数据库密码必须同步修改数据库账号，不能仅修改配置文件。

### AgentScope Studio 链路调试

```bash
docker compose -f compose.studio.yaml up -d
IWORK_STUDIO_ENABLED=true npm run dev
```

Studio 只在本机的 [13000 端口](http://127.0.0.1:13000/home)提供页面。前端和开发依赖已启动时，可在停止原后端后使用 `IWORK_STUDIO_ENABLED=true npm run dev:backend`。IDEA 调试则在后端运行配置的环境变量中添加 `IWORK_STUDIO_ENABLED=true`。

在 iWork 提交一条任务，完成后打开 Studio 的 Trace 页面。选择 `invoke_agent iWork` 链路可查看 Harness 执行、模型调用和工具调用，`gen_ai.agent.id` 对应 iWork 任务 ID。模型调用上报供应商返回且 SDK 能提取的输入与输出 token 用量。上下文摘要、沙箱清理和成果发布状态继续在 iWork 任务诊断中查询。

接入默认关闭。开启时通过 OTLP/HTTP 异步发送到本机 Studio，记录调用元数据和状态码。会话、模型及工具正文和异常详情留在 iWork 的原有处理链路。Studio 数据保存在独立 Docker 卷中，`docker compose -f compose.studio.yaml down` 停止服务并保留历史。

Java v2 文档中的 `StudioMessageHook` 会通过 HTTP 和 WebSocket 同步完整消息，并支持在 Studio 中等待人工输入。本项目通过 Harness 的中间件接口和独立的 OpenTelemetry tracer 向 Studio 提供 Trace。上游已停止维护独立 Studio，本地容器固定使用 1.0.9。 [Java v2 Studio](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/integration/ecosystem/studio.md) · [Studio OTLP 接入](https://agentscope-ai.github.io/agentscope-studio/develop/tracing.html) · [上游维护公告](https://github.com/agentscope-ai/agentscope-studio#archive-notice)

## 提交前检查

在仓库根目录运行；后端测试会启动临时真实 MySQL 和 Redis，需先启动 Docker。

```sh
npm ci --prefix frontend
npm --prefix frontend run format:check
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend exec -- playwright install chromium
npm --prefix frontend run test:e2e
npm --prefix frontend run build
backend/mvnw -B -f backend/pom.xml spotless:check
python3 scripts/verify-backend.py
```

`verify-backend.py` 构建独立测试沙箱和解析镜像，生成临时密钥，等待管理服务及看门狗就绪后执行后端全量测试，最后删除本次容器、卷和镜像。它会清理 `backend/target`，请勿与本地正在运行的后端共用同一检出目录；需先安装 Docker 并保证本机未对测试开放的端口设置防火墙拦截。
测试按类使用独立 JVM，防止 Spring 测试上下文在其 Testcontainers 数据库停止后继续后台重连；不使用自动重试来掩盖失败。

代码格式由固定版本的 Prettier 和 Spotless / google-java-format 统一，CI 分别检查前端和后端。修改前端格式使用 `npm --prefix frontend run format`；修改 Java 格式使用 `backend/mvnw -B -f backend/pom.xml spotless:apply`。检查命令只报告问题，不修改文件；格式化命令会改写对应源码。
当前 Prettier 对部分紧凑的 Vue 模板第一次格式化后仍可能产生差异，因此前端格式化命令执行两遍；升级工具时应重新验证这一限制。

## 更新 OpenAPI

启动 Docker 后，在仓库根目录运行以下命令。测试以临时 MySQL 和 Redis 启动后端，从 Spring MVC 读取 `/api/**`，补充登录表单、原始文件上传和 JSON 响应外层结构，并校验生成的 [openapi.yaml](../openapi.yaml)。

```sh
backend/mvnw -B -f backend/pom.xml -Dtest=ApplicationTest#openApiMatches -Diwork.openapi.update=true test
```

去掉 `-Diwork.openapi.update=true` 可只校验并检查文件是否过期。现有后端 CI 也会执行此检查。更新后将文件与相关接口改动一起提交，或在 Apifox 的 iWork Specs 工作区选择此文件导入。文档接口仅用于测试生成。

## 组件发布

在实际使用组件的页面验证变更，通过相关单元测试和关键流程的 Playwright 检查。Vue 工作台使用私有 npm 包，组件随主应用交付。发布时选定通过 CI 的 Git 提交，按部署指南构建对应镜像并验证该页面。[前端包配置](../frontend/package.json) · [构建与部署](DEPLOYMENT.md)

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

Flyway 在应用启动时执行迁移。首次部署从空 MySQL 库执行 `V1__baseline.sql`，创建完整业务表、约束、索引和初始配置。再次启动会校验 `flyway_schema_history`，校验失败会阻止启动。后续结构变更从 V2 开始追加迁移。使用过首发前 V1–V20 的开发或验收实例需改用新的空库和独立文件目录，原数据可保留给匹配的旧版本。

`-Pdistribution` 依赖先执行前端构建；开发时只检查 Java 可以不用该 profile。Docker 多阶段构建始终重新构建前端，不将本机 `node_modules`、`target`、`.env` 或数据目录放入构建上下文。最终镜像仅有运行时和 jar。

## 业务 HTTP 响应

`/api/**` 中有正文的业务 JSON 使用统一信封。成功示例为 `{"success":true,"data":{"id":"…"},"error":null}`，失败示例为 `{"success":false,"data":null,"error":"not_found"}`。HTTP 状态和 Cookie、CSRF 机制继续作为协议的一部分。204 无正文，文件下载成功时返回原始字节。

`GET /api/conversations/{id}/documents?page=0` 的 `data` 为 `{items,hasMore}`，每页最多 20 个版本，从第 0 页开始。任务对象的 `publishedVersion` 提供已发布版本的 ID、标题和序号。

`mvn verify` 在 `backend/target/site/jacoco/` 生成 JaCoCo 行覆盖率报告，合并测试 JVM 和管理员恢复测试启动的子 JVM。沙箱管理器与看门狗在独立容器运行，其内部执行未计入该报告。

## 历史验收记录

首版逐项规格、架构决策和验收原文可在本仓库提交 `41bdf6d770f569327575b8f44346327481c11798` 的 `docs/` 中查阅。比如，查看单人部署演练可运行 `git show 41bdf6d:docs/validation/issue25-ecs.md`。对应截图保存在 `docs/validation/`。

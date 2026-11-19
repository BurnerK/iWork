# iWork

自托管的 AI 工作应用，提供账号管理、个人会话、后台任务、模型授权与额度、项目空间、个人智能体、资料上传和沙箱解析，以及 Markdown 成果版本。

后端使用 AgentScope Java 2.0.4 HarnessAgent 管理上下文恢复与压缩。真实模型效果、目标 Linux 升级与灾难恢复仍需实环境验收。[运行机制](docs/adr/0004-harness-runtime-and-context.md) · [首版规格](docs/web-first-release-spec.md)

## 工具与目录

- Java 21；交付镜像 Temurin 21.0.13+9，镜像摘要固定在 `Dockerfile`。
- Maven 3.9.11 / Wrapper 3.3.4：使用 `backend/mvnw`，无需系统 Maven。
- Node 26.13.1 / npm 11.20.0：版本在 `.nvmrc` 和前端 `package.json`。
- Spring Boot 4.0.9 / AgentScope Java 2.0.4 / MySQL 8.4.7；Vue 3.5.45 / TypeScript 5.9.3 / Vite 8.3.8。前端传递依赖由 lockfile 固定，后端使用 Boot BOM。
- Docker Engine 与 Compose v2 或更高版本；Python 3 用于 Compose 验收脚本。

`backend/` 是正式 Java 主应用，`frontend/` 是 Vue 入口。`prototypes/` 只保留决策探针，不参与正式构建。模块职责见 [工程说明](docs/development.md)。

## 本地开发（推荐）

先安装上述 Java、Node/npm，并启动 Docker。macOS / Linux 可直接运行，Windows 使用 WSL；使用 nvm 时先执行 `nvm install && nvm use`。

在仓库根目录运行，无需先在根目录执行 `npm install`：

```sh
npm run setup
npm run dev
```

`setup` 安装前端锁定依赖，并首次生成权限为 `0600` 的 `.env.development`，包含随机数据库密码和管理员初始密码。重复执行保留已有配置；前端依赖变更后再次执行即可。根目录 npm 仅作为命令入口，没有额外依赖。

`dev` 检查应用端口，启动并等待独立 MySQL / Redis 就绪，再运行本机 Maven 后端和 Vite 前端；同一终端显示带来源标识的日志。后端 readiness 通过后输出就绪提示。首次 Maven 下载可能较慢，超时后可重试，构建错误保留在终端。

打开 **http://127.0.0.1:5173**，使用 `.env.development` 中的管理员账号和初始密码登录，首次改密后重新登录，并删除文件中的 `IWORK_BOOTSTRAP_PASSWORD` 配置。已有管理员不会被配置覆盖。前端支持热更新；修改 Java 后停止并重新运行，或使用 IDEA 调试。

| 命令 | 用途 |
| --- | --- |
| `npm run setup` | 安装前端依赖、首次生成开发配置 |
| `npm run dev` | 启动开发依赖和前后端 |
| `npm run dev:infra` | 只启动并等待 MySQL / Redis 就绪 |
| `npm run dev:backend` | 只运行后端，依赖需已启动 |
| `npm run dev:frontend` | 只运行前端 |
| `npm run dev:down` | 停止开发依赖，保留数据卷 |
| `npm run test:dev` | 检查开发脚本的配置保护、端口冲突和进程清理 |

按 **Ctrl+C** 停止本次启动的前后端及其子进程；数据库继续运行。需要停止数据库时，退出开发进程后运行 `npm run dev:down`。

开发依赖由 `compose.dev.yaml` 管理，项目名为配置中的 `iwork-dev-…`，使用独立命名卷，不复用交付环境的数据库或账号。默认 MySQL `127.0.0.1:13306`、Redis `127.0.0.1:16379`、后端 `127.0.0.1:8080`、前端 `127.0.0.1:5173`；文件保存在 `data/dev/files`。依赖端口可在 `.env.development` 中调整，前后端端口固定。应用端口冲突时会报错，不会终止其他项目进程。多个检出目录生成不同 Compose 项目名，但同时运行仍需处理端口冲突。

**IDEA 调试：**运行 `npm run dev:infra`，在后端运行配置中选择 JDK 21，将仓库根目录设为工作目录，并通过 IDEA 的环境文件功能加载 `.env.development`（不支持该功能时将变量填入 Environment variables）；运行 `IWorkApplication`。另一个终端执行 `npm run dev:frontend`。不要同时启动 `dev` 或 `dev:backend` 占用后端端口。

`.env.development` 已被 Git 忽略，仅开发脚本显式读取，普通 Java 启动不会自动加载。不要删除该文件来重置密码：已有 MySQL 卷不会随环境变量自动更改密码。`setup` 不启动数据库，也不会修改原有交付 `.env`。

## 独立检查与交付构建

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
backend/mvnw -B -f backend/pom.xml -Pdistribution -DskipTests package
```

独立后端构建无需前端工具。`distribution` 将已构建的 `frontend/dist` 打入 `backend/target/iwork-0.1.0-SNAPSHOT.jar`。完整容器构建自动执行前端检查并生成同一交付包；Java 集成测试在容器构建之外执行，主应用不挂载 Docker socket。

`verify-backend.py` 构建独立测试沙箱和解析镜像，生成临时密钥，等待管理服务及看门狗就绪后执行后端全量测试，最后删除本次容器、卷和镜像。它会清理 `backend/target`，请勿与本地正在运行的后端共用同一检出目录；需先安装 Docker 并保证本机未对测试开放的端口设置防火墙拦截。
测试按类使用独立 JVM，防止 Spring 测试上下文在其 Testcontainers 数据库停止后继续后台重连；不使用自动重试来掩盖失败。

代码格式由固定版本的 Prettier 和 Spotless / google-java-format 统一，CI 分别检查前端和后端。修改前端格式使用 `npm --prefix frontend run format`；修改 Java 格式使用 `backend/mvnw -B -f backend/pom.xml spotless:apply`。检查命令只报告问题，不修改文件；格式化命令会改写对应源码。
当前 Prettier 对部分紧凑的 Vue 模板第一次格式化后仍可能产生差异，因此前端格式化命令执行两遍；升级工具时应重新验证这一限制。

## 更新 OpenAPI

启动 Docker 后，在仓库根目录运行以下命令。测试以临时 MySQL 和 Redis 启动后端，从 Spring MVC 读取 `/api/**`，补充登录表单、原始文件上传和 JSON 响应外层结构，并校验生成的 [openapi.yaml](openapi.yaml)。

```sh
backend/mvnw -B -f backend/pom.xml -Dtest=ApplicationTest#openApiMatches -Diwork.openapi.update=true test
```

去掉 `-Diwork.openapi.update=true` 可只校验并检查文件是否过期。现有后端 CI 也会执行此检查。更新后将文件与相关接口改动一起提交，或在 Apifox 的 iWork Specs 工作区选择此文件导入。文档接口仅用于测试生成。

## 完整应用 Compose 启动（打包联调）

```sh
test -f .env || cp .env.example .env
# 编辑 .env，为数据库和初始管理员密码填写不同随机值（可分别运行 openssl rand -hex 24）。
# 仅 localhost HTTP 开发设置 IWORK_COOKIE_SECURE=false；生产 HTTPS 保持 true。
node scripts/sandbox-key.mjs  # 首次生成服务密钥；已有文件会拒绝覆盖
docker compose build
mkdir -p data/files
docker compose run --rm --no-deps --user 0 --cap-add CHOWN --entrypoint chown app 10001:10001 /data/files
docker compose up -d --wait --wait-timeout 180
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

打开 [本机入口](http://127.0.0.1:8080)，以外部配置的管理员账号登录并修改初始密码，然后移除初始密码配置。无内置默认密码或公开注册。应用仅绑定回环地址；MySQL、Redis 和沙箱管理服务不发布宿主端口。生产通过 HTTPS 反向代理接入。

`data/mysql` 与 `data/files` 是宿主持久目录，Redis 使用 Compose 命名卷 `redis-data` 保存登录态（AOF）。若修改 `IWORK_DATA_DIR`，需在对应目录下创建 `files`；初始化命令会按实际挂载路径设置 UID/GID 10001。主应用始终以该非 root 用户运行。不要将 `data/` 或 `.env` 提交到 Git；不要删除目录来解决启动失败。

```sh
docker compose stop       # 停止，保留数据
docker compose start     # 再次启动，Flyway 验证历史且不重复应用 V1
docker compose down      # 删除本项目容器及网络，仍保留宿主数据目录
```

修改数据库密码环境变量不会修改已初始化 MySQL 的账号密码；轮换需要同步更新数据库账号与应用配置。

## 自动化运行验收

```sh
python3 scripts/verify-compose.py
```

脚本创建独立项目、随机测试凭据和临时端口，在系统临时目录的 `codex/iwork-smoke-*` 下保存测试数据与通过记录。验证迁移、初始管理员幂等、首次改密、会话持久化、应用及 Redis 重建保留登录、依赖中断、Redis 丢失后重新登录、部署恢复撤权及日志不含测试密码。无论成功失败都停止该测试项目并删除测试 Redis 卷，不停止已有部署；测试目录保留便于排错。

GitHub Actions 使用上述相同的前端检查、Maven 验证与 Compose 脚本。完整 Linux 部署、升级和数据库/文件协调恢复属于 [#25](https://github.com/BurnerK/iWork/issues/25)，本票验证不替代上线验收。

更多本地开发、配置和排错说明见 [docs/development.md](docs/development.md)。

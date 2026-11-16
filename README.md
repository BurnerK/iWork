# iWork

自托管的 AI 工作应用。当前正式工程提供可构建的 Web 入口、MySQL 迁移、健康检查和单机 Compose 配置；账号、会话、模型和资料处理业务随后续工单交付。

## 工具与目录

- Java 21；交付镜像 Temurin 21.0.13+9，镜像摘要固定在 `Dockerfile`。
- Maven 3.9.11 / Wrapper 3.3.4：使用 `backend/mvnw`，无需系统 Maven。
- Node 26.13.1 / npm 11.20.0：版本在 `.nvmrc` 和前端 `package.json`。
- Spring Boot 3.5.16 / AgentScope Java core 2.0.4 / MySQL 8.4.7；Vue 3.5.45 / TypeScript 5.9.3 / Vite 8.3.8。前端传递依赖由 lockfile 固定，后端使用 Boot BOM。
- Docker Engine 与 Compose v2 或更高版本；Python 3 用于 Compose 验收脚本。

`backend/` 是正式 Java 主应用，`frontend/` 是 Vue 入口。`prototypes/` 只保留决策探针，不参与正式构建。模块职责见 [工程说明](docs/development.md)。

## 独立检查与交付构建

在仓库根目录运行；后端测试会启动临时真实 MySQL，需先启动 Docker。

```sh
npm ci --prefix frontend
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
backend/mvnw -B -f backend/pom.xml verify
backend/mvnw -B -f backend/pom.xml -Pdistribution -DskipTests package
```

独立后端构建无需前端工具。`distribution` 将已构建的 `frontend/dist` 打入 `backend/target/iwork-0.1.0-SNAPSHOT.jar`。完整容器构建自动执行前端检查并生成同一交付包；Java 集成测试在容器构建之外执行，主应用不挂载 Docker socket。

## 本地 Compose 启动

```sh
cp .env.example .env
# 编辑 .env，为两个密码填写不同随机值（可分别运行 openssl rand -hex 24）。
docker compose build
mkdir -p data/files
docker compose run --rm --no-deps --user 0 --cap-add CHOWN --entrypoint chown app 10001:10001 /data/files
docker compose up -d --wait --wait-timeout 180
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

打开 <http://127.0.0.1:8080>，应显示“服务已就绪”；点击“检查连接”可重试。仅绑定本机回环地址，MySQL 不发布宿主端口。未实现的业务入口默认拒绝访问，没有可登录的默认账号。

`data/mysql` 与 `data/files` 是宿主持久目录。若修改 `IWORK_DATA_DIR`，需在对应目录下创建 `files`；上面的初始化命令会按 Compose 实际挂载路径设置 UID/GID 10001。普通主应用始终以该非 root 用户运行。不要将 `data/` 或 `.env` 提交到 Git；不要删除目录来解决启动失败。

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

脚本创建独立项目、随机测试凭据和临时端口，在系统临时目录的 `codex/iwork-smoke-*` 下保存测试数据与通过记录。验证干净初始化、唯一 V1 迁移、前端入口、拒绝诊断访问、重建后数据库与文件保留、停止 MySQL 后就绪 503/存活 200、恢复后就绪 200，以及日志不含测试密码。无论成功失败都停止该测试项目，不停止已有部署；测试目录保留便于排错。

GitHub Actions 使用上述相同的前端检查、Maven 验证与 Compose 脚本。完整 Linux 部署、升级和数据库/文件协调恢复属于 [#25](https://github.com/BurnerK/iWork/issues/25)，本票验证不替代上线验收。

更多本地开发、配置和排错说明见 [docs/development.md](docs/development.md)。

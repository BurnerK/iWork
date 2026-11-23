# iWork 构建与部署

iWork 使用单台 Linux 主机和 Docker Compose 部署。发布版包含主应用、沙箱管理服务和文本解析器三个镜像。Vue 工作台随主应用交付，执行看门狗复用沙箱管理服务镜像，MySQL 和 Redis 使用 Compose 配置锁定的镜像。

## 1. 构建并交付镜像

在发布提交对应的干净检出目录执行。镜像应与部署主机的 CPU 架构一致。构建前确认该提交已通过 GitHub Actions 的前端、后端和 Compose 检查。[CI 工作流](../.github/workflows/build.yml) · [本地检查](DEVELOPMENT.md#提交前检查)

```sh
# 将示例地址换成部署主机可访问的镜像库命名空间。
iwork_registry=registry.example.com/team
iwork_revision="$(git rev-parse HEAD)"

docker build --target app -t "${iwork_registry}/iwork-app:${iwork_revision}" .
docker build --target sandbox-manager -t "${iwork_registry}/iwork-sandbox-manager:${iwork_revision}" .
docker build -f sandbox/Dockerfile.worker -t "${iwork_registry}/iwork-text-parser:${iwork_revision}" sandbox

docker push "${iwork_registry}/iwork-app:${iwork_revision}"
docker push "${iwork_registry}/iwork-sandbox-manager:${iwork_revision}"
docker push "${iwork_registry}/iwork-text-parser:${iwork_revision}"
```

记录 Git 提交和镜像库返回的三个摘要。部署时使用 `仓库/镜像@sha256:摘要`。应用镜像构建会运行前端类型检查和单元测试，再将 Vite 产物打入 Spring Boot JAR。[应用和管理镜像](../Dockerfile) · [文本解析器镜像](../sandbox/Dockerfile.worker)

## 2. 准备首装配置

在部署主机保留与发布提交对应的仓库配置和脚本。准备 Docker Engine、支持 `!reset` 的 Compose 和 HTTPS 反向代理。首次生成沙箱密钥需要 Node.js，后续备份与恢复脚本需要 Python 3。

| 配置 | 首装要求 |
| --- | --- |
| 发布环境文件 | 参考 [`.env.example`](../.env.example) 创建 `/secure/iwork.env`，权限为 `0600`。为 `IWORK_DB_PASSWORD`、`IWORK_MYSQL_ROOT_PASSWORD` 和 `IWORK_BOOTSTRAP_PASSWORD` 分别设置随机值。 |
| 发布镜像 | 在同一环境文件中设置 `IWORK_APP_IMAGE`、`IWORK_MANAGER_IMAGE`、`IWORK_PARSER_IMAGE`，分别填入上一节记录的完整镜像引用。 |
| 持久数据 | 设置独立的 `IWORK_DATA_DIR`，例如 `/srv/iwork`。MySQL 在该目录下使用 `mysql/`，上传资料和成果使用 `files/`。提前创建 `files/`，并让应用的 UID/GID `10001:10001` 可读写。Redis 登录态使用 Compose 命名卷。 |
| 沙箱 | 设置该 Docker 主机上唯一且稳定的 `IWORK_SANDBOX_INSTANCE`。首次可用 `node scripts/sandbox-key.mjs /secure/iwork-secrets/sandbox.key` 生成密钥，再将 `IWORK_SANDBOX_KEY_FILE` 指向该文件。原实例升级沿用已有密钥。 |
| 模型 | 在独立的 `/secure/iwork-model.env` 配置同名的 `IWORK_MODEL_KEY_<名称>` 和 `IWORK_MODEL_URL_<名称>`。通过私有 Compose 覆盖文件把该环境文件只注入 `app`，URL 与获准使用的 HTTPS 基础地址保持一致。[模型环境文件示例](#模型环境文件) |
| HTTPS | 保持 `IWORK_COOKIE_SECURE=true`，由 HTTPS 代理转发到宿主回环地址上的应用端口，默认 `127.0.0.1:8080`。代理需要让 AG-UI 的 SSE 事件及时到达浏览器。 |

首次安装使用空 MySQL 库，由 Flyway 创建表结构。使用过首发前 V1–V20 的验收实例时，应使用独立的数据目录、数据库和 Redis 卷。

## 3. 启动并检查

以下示例以 `iwork` 为 Compose 项目名，在发布版仓库根目录执行。先按上节准备环境文件和私有模型覆盖文件。后续检查、升级和停止操作使用同一组配置文件和项目名。

```sh
iwork_release() {
  docker compose --env-file /secure/iwork.env -p iwork \
    -f compose.yaml -f compose.release.yaml -f /secure/iwork-model.compose.yaml "$@"
}

iwork_release config --quiet
iwork_release config --images
iwork_release pull
iwork_release up -d --no-build --pull never --wait --wait-timeout 180
iwork_release ps -a
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

如果修改了 `IWORK_HTTP_PORT`，用实际端口探测。解析器初始化服务 `text-parser-image` 会在检查 Python 版本后正常退出。

1. 通过 HTTPS 登录，修改初始管理员密码，然后从部署环境文件移除 `IWORK_BOOTSTRAP_PASSWORD`。
2. 配置部署要使用的上游模型，为验收账号配置模型授权和额度。提交一条任务，确认回复能逐段显示、任务能完成，刷新后仍能查到结果。
3. 如果本次部署提供资料解析和文档功能，上传受支持的 TXT 或 Markdown 资料，检查解析和容器清理状态，再生成一份成果并从成果卡片预览、下载对应版本。

## 4. 升级与恢复

1. 在维护窗口前预拉取新镜像，记录当前和目标镜像摘要。进入维护窗口后阻止新请求，停止 `app` 和其他业务写入方。保留沙箱管理服务和看门狗，直至确认本实例的处理容器已经回收。
2. 保持 MySQL 运行，用 `scripts/backup.py backup` 在同一次备份中保存业务库和文件目录，保留校验清单及上一版镜像引用。发布配置和凭据另行保管。[备份命令与参数](#可执行协调备份与恢复)
3. 切换镜像引用并启动，复查迁移、readiness 和上述业务验收项。升级前仍在运行的任务应标为中断，已保存的历史、原文和成果版本应可查回。
4. 迁移失败时停止应用并排查。需要恢复时，在独立的空数据库和文件目录中恢复并校验原备份，用与备份配套的镜像启动。检查账号、历史、原文和成果后，再决定是否切换流量。[恢复与校验命令](#可执行协调备份与恢复)

已有 ECS 演练覆盖单任务升级、协调恢复和沙箱回收。生产 HTTPS、异地备份、跨机器恢复和持续并发仍需在目标环境验证。演练通过 SSH 隧道访问，恢复到同一主机上的独立 Compose 项目。


## 模型环境文件

用私有 Compose 文件 `/secure/iwork-model.compose.yaml` 将模型密钥仅传给 app。对应的 `/secure/iwork-model.env` 权限设为 0600，由部署者配置 `IWORK_MODEL_KEY_<名称>` 和同名的 `IWORK_MODEL_URL_<名称>`。URL 必须与该密钥获准使用的 HTTPS 基础地址完全一致，包括路径和末尾斜线。升级前为每个已用密钥补齐 URL，缺少绑定或地址不匹配的模型会拒绝保存和调用。服务商若返回重定向，请由部署者直接绑定经批准的最终地址。

```yaml
services:
  app:
    env_file: /secure/iwork-model.env
```

## 可执行协调备份与恢复

`scripts/backup.py` 仅接受运行的 mysql 与停止的 app；`--writers-stopped` 表示部署者已核实所有额外写入进程停止。运行前核对应用实际使用的业务库。若与 MySQL 容器的 `MYSQL_DATABASE` 不同，在 backup、restore、verify 各自通过 `--database` 指明该实例的实际业务库。脚本不代替维护窗口，也不自动停止或重启应用。

```sh
# 示例路径必须替换为授权实例；勿复制测试凭据。
python3 scripts/backup.py backup --compose compose.yaml --compose compose.release.yaml \
  --compose /secure/iwork-model.compose.yaml --env-file /secure/iwork.env \
  --project iwork --files /srv/iwork/files --backup /secure/backups/iwork-RELEASE \
  --writers-stopped
```

备份包含MySQL完整业务schema（含上下文/摘要/调用账本/项目引用）、文件目录和校验manifest；先检查全部原文/正式版本的DB索引、大小与SHA256，写入单次逻辑数据库快照和tar，再核对。目录已存在则拒绝覆盖。失败目录没有完成manifest，不作为可用备份。备份默认0700/0600，不含模型密钥或登录Redis；凭据与发布配置由部署者另行受控备份。

只在**独立且空**的恢复项目中先启动 mysql，保持 app 停止。恢复环境文件单独设置 `IWORK_DATA_DIR=/srv/iwork-restore`、HTTP 端口、沙箱实例名和实际 key 路径，使用独立项目及其 Redis 和沙箱卷。先用来自备份版本的镜像验证恢复。新目标 MySQL 密码独立配置，恢复 SQL 只含业务库。启动前核对 Compose 实际解析的 MySQL 和文件挂载，确认路径与源实例分离。以下命令只输出挂载、沙箱实例名和 key 路径。

```sh
iwork_restore() {
  docker compose --env-file /secure/restore.env -p iwork-restore \
    -f compose.yaml -f compose.release.yaml "$@"
}
iwork_restore config --format json | python3 -c '
import json, sys
from pathlib import Path
config = json.load(sys.stdin)
services = config["services"]
for service, target in [("mysql", "/var/lib/mysql"), ("app", "/data/files")]:
    source = next(mount["source"] for mount in services[service]["volumes"]
                  if mount["type"] == "bind" and mount["target"] == target)
    print(service, Path(source).resolve())
print("sandbox instance", services["sandbox-manager"]["environment"]["IWORK_SANDBOX_INSTANCE"])
print("sandbox key path", Path(config["secrets"]["sandbox_key"]["file"]).resolve())
'
# 核对 /srv/iwork-restore/mysql、/srv/iwork-restore/files、专用实例名和 key 路径后再启动。
iwork_restore up -d --wait mysql
# 先创建空的 /srv/iwork-restore/files，并确保执行者可读写。
python3 scripts/backup.py restore --compose compose.yaml --compose compose.release.yaml \
  --env-file /secure/restore.env \
  --project iwork-restore --files /srv/iwork-restore/files --backup /secure/backups/iwork-RELEASE \
  --writers-stopped
python3 scripts/backup.py verify --compose compose.yaml --compose compose.release.yaml \
  --env-file /secure/restore.env \
  --project iwork-restore --files /srv/iwork-restore/files --backup /secure/backups/iwork-RELEASE \
  --writers-stopped
```

脚本校验 SQL/tar 哈希，拒绝原项目、非空数据库/文件目录、符号链接、特殊文件和路径逃逸。恢复失败时目标保持离线并保留现场，重新演练须另选空目标。恢复文件由执行者拥有。核对通过后递归将恢复文件目录的属主设为 10001:10001，再用恢复实例自己的模型配置启动全栈。

```sh
iwork_restore -f /secure/iwork-restore-model.compose.yaml pull
iwork_restore -f /secure/iwork-restore-model.compose.yaml up -d --no-build --pull never --wait --wait-timeout 180
```

通过页面登录、找回并下载原文和旧版本、显式提交续改，核对快照、用量和正式版本。

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

资料上传默认每账号累计 100 MiB、1,000 份，实例原文目录累计 10 GiB、10,000 份，并在写入前为共享文件系统保留 512 MiB 可用空间。实例额度包含目录里的遗留文件。超出账号额度返回 `409 material_quota_exceeded`，实例容量不足返回 `503 material_storage_unavailable`。已有资料仍可下载。Java 环境变量 `IWORK_MATERIAL_OWNER_MAX_BYTES`、`IWORK_MATERIAL_OWNER_MAX_FILES`、`IWORK_MATERIAL_INSTANCE_MAX_BYTES`、`IWORK_MATERIAL_INSTANCE_MAX_FILES` 和 `IWORK_MATERIAL_MIN_FREE_BYTES` 可覆盖默认值。在 Compose 部署中，通过应用服务的 `environment` 传入这些变量。

每个会话默认最多等待 20 条任务，每个账号跨会话合计默认最多等待 100 条。可用 Java 环境变量 `IWORK_TASK_CONVERSATION_QUEUE_LIMIT`（1–20）和 `IWORK_TASK_ACCOUNT_QUEUE_LIMIT`（至少 1）调整。在 Compose 部署中，通过应用服务的 `environment` 传入这些变量。队列达到上限后，新排队请求返回 `429 task_capacity_exceeded`。待发送项按发送顺序一次返回，调低限额后仍可执行或移除已有项，同一请求的重试仍返回原任务。

## 基础排错

- `docker compose ps` 检查服务状态；`docker compose logs --tail=100 app mysql` 查看启动或迁移失败。不要复制包含私密数据的日志到公开位置。
- `/actuator/health/liveness` 仅表明进程存活；`/actuator/health/readiness` 同时检查数据库、Redis 和文件目录，失败返回 503。数据库启动失败时主应用不启动；运行中依赖失联不触发进程重启，恢复后可恢复就绪。
- 文件目录不可写时检查宿主目录和 UID/GID 10001；初始化 `chown` 命令需临时添加 `CHOWN` capability，单用 root 仍会被应用的 `cap_drop: ALL` 限制。不要让主应用永久以 root 运行或保留该 capability。
- 数据库认证失败时检查实际账号密码与环境是否一致；连接超时需检查网络和 MySQL 状态。连接建立/读写超时 3 秒，连接池获取超时 3 秒；健康探测还有调度与连接池开销，不把这些配置当作完整响应 SLA。
- Flyway 校验失败时还原误改的旧迁移并新增迁移，不删除历史表或已有数据。
- macOS Testcontainers 找不到 Docker 时，确认 Docker 引擎已启动；必要时将 `DOCKER_HOST` 指向 `docker context inspect` 显示的当前 socket，勿挂载到主应用。

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
docker compose start     # 再次启动，Flyway 校验已应用的首发基线与后续迁移
docker compose down      # 删除本项目容器及网络，仍保留宿主数据目录
```

修改数据库密码环境变量不会修改已初始化 MySQL 的账号密码；轮换需要同步更新数据库账号与应用配置。

## 自动化运行验收

```sh
python3 scripts/verify-compose.py
```

脚本创建独立项目、随机测试凭据和临时端口，在系统临时目录的 `codex/iwork-smoke-*` 下保存测试数据与通过记录。验证迁移、初始管理员幂等、首次改密、会话持久化、应用及 Redis 重建保留登录、依赖中断、Redis 丢失后重新登录、部署恢复撤权及日志不含测试密码。无论成功失败都停止该测试项目并删除测试 Redis 卷，不停止已有部署；测试目录保留便于排错。

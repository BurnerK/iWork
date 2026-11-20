# #25 部署、升级与协调恢复

[阿里云 ECS 单人验收记录](validation/issue25-ecs.md)记录通过 SSH 隧道执行的升级、恢复和沙箱演练。[本地恢复演练](validation/issue25-local-restore.md)保留前期验证记录。正式发布仍需按实际环境确认镜像分发、HTTPS 和备份加密保留策略。

## 部署者提供的配置

- 独立 Linux/Docker/Compose 访问入口及可操作范围；确认 root/Docker 管理权限仅交付可信部署者。
- 指定发布 Git commit 与三个镜像（app、sandbox-manager、text-parser）的不可变标签/摘要。源 Dockerfile 和 mysql/redis 已锁定基础版本，不用旧测试镜像替代发布镜像。
- 新的数据库用户/管理员密码、沙箱服务密钥（与实例绑定）、实际模型凭据；只经受控本地文件或服务配置分发，不放入聊天、Git 或 Vite。模型环境只传给 app。
- 独立持久目录、服务 UID/GID 10001、备份位置与读取主体、恢复环境；确认备份含用户私人内容及密码哈希，必须按业务数据保护。
- 确认维护窗口、应用容量、预期并发、观察负载和期限余量。不能将本机观察值当 SLA。

## 发布与升级

根 `compose.yaml` 运行 app、MySQL、登录 Redis、管理服务、看门狗和构建解析器镜像的初始化任务。仅 app 绑定回环HTTP端口；生产保持Secure Cookie并在前置HTTPS代理发布。管理服务不发布宿主端口，主应用无Docker socket；管理器和看门狗有高权限，须独立保护。

1. 参考 [`.env.example`](../.env.example) 在授权路径准备发布环境文件（0600），配置数据库密码、专用数据目录及沙箱实例名。首次启动空数据库前设置 `IWORK_BOOTSTRAP_PASSWORD`，管理员登录并改密后移除。在同一文件将 `IWORK_APP_IMAGE`、`IWORK_MANAGER_IMAGE`、`IWORK_PARSER_IMAGE` 分别设为已批准的 `仓库/镜像@sha256:摘要`。使用 `compose.release.yaml` 禁用源码构建，管理服务与看门狗共用同一管理镜像，管理服务按同一解析器引用启动工作容器。首次部署用 `node scripts/sandbox-key.mjs /secure/iwork-secrets/sandbox.key` 在 0700 目录生成只读 key，并设置 `IWORK_SANDBOX_KEY_FILE=/secure/iwork-secrets/sandbox.key`。常规升级沿用该实例的 key。确保 app 的 UID 10001 与管理服务可读。所有 Compose 命令使用同一组配置文件、环境文件和项目名。
2. 在目标机器预拉取已确认的发布镜像，再核对镜像清单和实际构建产物。确认 core、harness、model-openai 都为 2.0.4、默认工具关闭、生产 JAR 无测试 Fixture。首次启动前在宿主上创建 `IWORK_DATA_DIR/files`，给 UID/GID 10001 读写权限。应用保持非 root、只读根文件系统和无额外 capabilities。
3. 升级前阻止外部新请求并停止 app，停止其他本地/定时写入进程。保留看门狗与管理服务直至本实例处理容器实际不存在；核对诊断与Docker标签。维护期运行任务按中断处理，不能自动恢复执行。
4. 按下节做协调备份，保留旧镜像摘要及备份校验和，再部署新镜像。不能仅凭同路径覆盖JAR宣称升级完成。
5. 启动后查看 readiness、迁移与受限诊断。真实登录查回用户、任务、历史、原文与成果，下载对比。运行中的旧任务应显示中断，旧 requestId 不得产生新调用。通过新请求验证会话上下文仍可使用。Redis 不兼容时重新登录，业务数据由 MySQL 和文件备份恢复。
6. 若启动时迁移失败，执行 `iwork_release stop app`，保持应用停止并定位。不得修改已应用迁移、自动 clean、忽略校验或对未知数据库 baseline。若新版本已写数据，在独立恢复环境恢复整套旧备份并核对，再由部署者决定是否切换。

用私有 Compose 文件 `/secure/iwork-model.compose.yaml` 将模型密钥仅传给 app。对应的 `/secure/iwork-model.env` 权限设为 0600，由部署者配置 `IWORK_MODEL_KEY_<名称>` 和同名的 `IWORK_MODEL_URL_<名称>`。URL 必须与该密钥获准使用的 HTTPS 基础地址完全一致，包括路径和末尾斜线。升级前为每个已用密钥补齐 URL，缺少绑定或地址不匹配的模型会拒绝保存和调用。服务商若返回重定向，请由部署者直接绑定经批准的最终地址。

```yaml
services:
  app:
    env_file: /secure/iwork-model.env
```

从发布版仓库根目录执行。Compose 优先使用当前 shell 已导出的变量，请在没有导出其他实例 `IWORK_*` 变量的部署 shell 操作。可在维护窗口前预拉取镜像。

```sh
iwork_release() {
  docker compose --env-file /secure/iwork.env -p iwork \
    -f compose.yaml -f compose.release.yaml -f /secure/iwork-model.compose.yaml "$@"
}
iwork_release config --quiet
# 只记录镜像引用，避免输出包含已展开凭据的完整配置。
iwork_release config --images
iwork_release pull
```

升级时在反向代理拦截新请求，执行 `iwork_release stop app` 并按下节完成协调备份。首装或升级备份完成后启动。

```sh
iwork_release up -d --no-build --pull never --wait --wait-timeout 180
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

## 目标环境记录

实际完成升级一次、恢复一次，并记录CPU/内存/磁盘、Docker/Compose版本、镜像摘要、并发、材料大小、任务ID与观测时间。重验容器删除拒绝/通信失败、管理器重启、主应用强杀、看门狗健康及过期回收；条件包含Docker/宿主调度和时钟可用。断电和全宿主失效若未实测必须明确未验证。恢复后重新检查授权、预算、历史、快照、原文和新旧版本，不能把readiness或HTTP200代替业务验收。

注意历史测试环境在UTC会话修复前可能写入偏移的 `users.locked_until`。迁移正式旧环境时先核查时间与实际锁定，不能批量改写用户数据。

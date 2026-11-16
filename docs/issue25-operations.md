# #25 部署与协调恢复准备（尚未完成目标验收）

目标 Linux、正式镜像发布位置/摘要、CPU/内存/磁盘容量、运行身份、HTTPS域名、模型密钥分发、备份存放位置/加密保留策略、恢复项目尚未指定。不能借用其他项目服务或现有测试凭据。本文和[本地实际恢复演练](validation/issue25-local-restore.md)不代表 #25 关闭。

## 部署者提供的配置

- 独立 Linux/Docker/Compose 访问入口及可操作范围；确认 root/Docker 管理权限仅交付可信部署者。
- 指定发布 Git commit 与三个镜像（app、sandbox-manager、text-parser）的不可变标签/摘要。源 Dockerfile 和 mysql/redis 已锁定基础版本，不用旧测试镜像替代发布镜像。
- 新的数据库用户/管理员密码、沙箱服务密钥（与实例绑定）、实际模型凭据；只经受控本地文件或服务配置分发，不放入聊天、Git 或 Vite。模型环境只传给 app。
- 独立持久目录、服务 UID/GID 10001、备份位置与读取主体、恢复环境；确认备份含用户私人内容及密码哈希，必须按业务数据保护。
- 确认维护窗口、应用容量、预期并发、观察负载和期限余量。不能将本机观察值当 SLA。

## 发布与升级

根 `compose.yaml` 运行 app、MySQL、登录 Redis、管理服务、看门狗和构建解析器镜像的初始化任务。仅 app 绑定回环HTTP端口；生产保持Secure Cookie并在前置HTTPS代理发布。管理服务不发布宿主端口，主应用无Docker socket；管理器和看门狗有高权限，须独立保护。

1. 在授权路径准备 `.env`（0600）和沙箱 key。先 `docker compose --env-file .env config --quiet` 校验，禁止把含展开凭据的完整 config 贴入日志。为 app 写单独的私有 override/env_file 传入实际模型键；不把模型凭据传给管理器、看门狗或前端。
2. 构建或加载明确摘要的发布镜像。核对实际 core/harness/model-openai 都为 2.0.4，默认工具仍关闭、生产JAR没有测试Fixture。初始化文件目录为UID/GID10001可读写；仅初始化步骤临时授予CHOWN，应用保持非root/read_only/cap_drop。
3. 升级前阻止外部新请求并停止 app，停止其他本地/定时写入进程。保留看门狗与管理服务直至本实例处理容器实际不存在；核对诊断与Docker标签。维护期运行任务按中断处理，不能自动恢复执行。
4. 按下节做协调备份，保留旧镜像摘要及备份校验和，再部署新镜像。不能仅凭同路径覆盖JAR宣称升级完成。
5. 启动后查看readiness、迁移与受限诊断；真实登录查回用户、任务、历史、原文与成果，下载对比。显式新任务才继续工作，旧requestId不得产生新调用。Redis不兼容时重新登录，不恢复旧Session作为业务恢复依据。
6. 迁移失败保持停止并定位；不得修改已应用迁移、自动clean、忽略校验或对未知数据库baseline。若新版本已写数据，不直接回退旧JAR：在独立恢复环境恢复整套旧备份并核对，再由部署者决定切换。

## 可执行协调备份与恢复

`scripts/backup.py` 仅接受运行的 mysql 与停止的 app；`--writers-stopped` 表示部署者已核实所有额外写入进程停止。脚本不代替维护窗口，也不自动停止或重启应用。

```sh
# 示例路径必须替换为授权实例；勿复制测试凭据。
python3 scripts/backup.py backup --compose compose.yaml --env-file /secure/iwork.env \
  --project iwork --files /srv/iwork/files --backup /secure/backups/iwork-RELEASE \
  --writers-stopped
```

备份包含MySQL完整业务schema（含上下文/摘要/调用账本/项目引用）、文件目录和校验manifest；先检查全部原文/正式版本的DB索引、大小与SHA256，写入单次逻辑数据库快照和tar，再核对。目录已存在则拒绝覆盖。失败目录没有完成manifest，不作为可用备份。备份默认0700/0600，不含模型密钥或登录Redis；凭据与发布配置由部署者另行受控备份。

只在**独立且空**的恢复项目中先启动 mysql，保持 app 停止。配置独立数据库卷、Redis、文件目录、沙箱实例标签/注册卷和服务key，避免接触源实例。新目标MySQL账号密码独立配置，恢复SQL只含业务库，不覆盖服务器账号。

```sh
python3 scripts/backup.py restore --compose compose.restore.yaml --env-file /secure/restore.env \
  --project iwork-restore --files /srv/iwork-restore/files --backup /secure/backups/iwork-RELEASE \
  --writers-stopped
python3 scripts/backup.py verify --compose compose.restore.yaml --env-file /secure/restore.env \
  --project iwork-restore --files /srv/iwork-restore/files --backup /secure/backups/iwork-RELEASE \
  --writers-stopped
```

脚本校验SQL/tar哈希，拒绝原项目、非空数据库/文件目录、符号链接/特殊文件/路径逃逸；不执行自动删除。恢复失败保留目标离线和部分状态，重新演练须另选空目标。备份是部署者控制的可信SQL，不从不可信上传附件恢复。恢复文件由执行者拥有；启动应用前按目标Linux身份把文件目录设为10001:10001，保留仅必要权限。行数与所有正式文件SHA核对通过后仍须做登录、页面、下载和显式续改业务验收；verify不能替代这些步骤。

## 仍需目标环境记录

实际完成升级一次、恢复一次，并记录CPU/内存/磁盘、Docker/Compose版本、镜像摘要、并发、材料大小、任务ID与观测时间。重验容器删除拒绝/通信失败、管理器重启、主应用强杀、看门狗健康及过期回收；条件包含Docker/宿主调度和时钟可用。断电和全宿主失效若未实测必须明确未验证。恢复后重新检查授权、预算、历史、快照、原文和新旧版本，不能把readiness或HTTP200代替业务验收。

注意历史测试环境在UTC会话修复前可能写入偏移的 `users.locked_until`。迁移正式旧环境时先核查时间与实际锁定，不能批量改写用户数据。

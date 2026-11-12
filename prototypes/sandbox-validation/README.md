# #8 · 官方 Docker 沙箱接入验证

可丢弃探针，合成资料与模拟凭据。**验证已完成，结论为需调整接入设计；不代表首版隔离验收通过。** 2026-11-12 的结果已提交审阅，Docker 管理权限边界仍需用户决定；#8、#6 保持开放。

## 复跑

本机需要 JDK 21、Maven 3.9+、Python 3.9+、可访问的本地 Docker Unix socket 和 Compose。基础镜像已固定 digest，首次需要拉取公开镜像和 Maven 依赖。仅在可信开发环境执行：探针主容器会挂载当前 Docker socket，拥有该引擎的完整管理权限。

```sh
cd prototypes/sandbox-validation
python3 verify.py
```

脚本构建两个 `iwork-sandbox-*:local` 镜像，由 Compose 启动 Java 主应用探针，再由真实 `agentscope-harness:2.0.4` 的 `DockerSandbox` 创建任务容器。只注册一个无路径、无命令参数的 Java 工具 `convert_document`，通过真实 SDK Toolkit 调度。没有使用 HarnessAgent 默认工具集，不需真实模型。受控工具执行容器镜像内的固定 Python 文本转 Markdown 程序。

系统临时证据位于 `$TMPDIR/codex/iwork-sandbox-*`，运行结束打印路径。运行中的容器仅按本轮唯一标签清理；保留镜像、构建缓存和证据。不读取旧原型的 `model.local.json`，不调用其他项目的服务。构建产物在被忽略的 `target/`。

## 版本与主机条件

本次宿主 macOS 27.2 arm64，OrbStack 的 Linux/arm64 引擎，内核 `7.2.12-orbstack-00394-gbecdd6130068`，cgroup v2。宿主 Java `21.0.13.0.3`、Maven `3.10.0`；**实际 Compose 主应用 Java `21.0.12.1`**。Docker Engine `29.8.2`，宿主 CLI `29.8.2`，主应用内 CLI `29.9.3`，Compose `v5.5.0`。基础镜像及实际构建镜像摘要见 [environment.json](evidence/20261112/environment.json)。JRE 基础摘要为本次 arm64 实测镜像，其他架构需重新选择镜像并验证。

SDK 为官方 Maven `io.agentscope:agentscope-harness:2.0.4`。源码核查 tag `v2.0.4` 对应 commit `0b6743b263ba8e39eb417e35048026f1e0196548`。官方依据：

- [DockerSandbox.java](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/sandbox/impl/docker/DockerSandbox.java)：CLI 管理容器、归档传输、超时杀本地进程、shutdown 清理。
- [AbstractBaseSandbox.java](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/sandbox/AbstractBaseSandbox.java)：stop 只保存快照并修改 SDK 标记。
- [固定版本文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/filesystem.md)：沙箱与文件系统接入背景；本报告的执行事实以实际探针为准。

## 实测与失败证据

[完整命令与输出](evidence/20261112/commands.json)、[17 组 SDK/业务/隔离检查](evidence/20261112/suite.json)、[主进程崩溃](evidence/20261112/crash-observation.json)、[重启清理](evidence/20261112/recovery.json)、[总结果](evidence/20261112/result.json)。`probePassed=true` 表示这些正反向断言符合观察结果，**不表示 SDK 默认生命周期或生产部署已通过验收**。

| 检查 | 实际结果 | 接入含义 |
| --- | --- | --- |
| Compose 到官方沙箱 | Java 主容器挂载 Docker socket，通过 CLI 创建兄弟任务容器 | socket 是引擎级管理权；限制处理容器不能限制主应用 |
| 受控工具 | 真实 SDK Toolkit 调用 `convert_document`；结果包含与容器 ID 对应的 hostname、容器 PID、UID 1000，生成正确 Markdown | 自定义 Java 工具显式调用沙箱；不会自动迁入容器 |
| 工具面与数据隔离 | Alice 两个任务及 Bob 一个任务同时存在，各自只返回自己的合成资料；Bob 对 Alice 的访问被应用拒绝；SDK 拒绝跨目录路径；未注册的 shell 返回错误 | 身份断言是合成应用规则，容器隔离与工具注册是实际 SDK/Docker 行为 |
| 文件、凭据与权限 | 任务容器没有 host bind mount；主应用私有文件、模拟数据库/模型环境变量、Docker socket 均不可见；非 root、只读根、cap-drop、no-new-privileges、seccomp 生效 | 任务只能获得归档传入的资料；输出导出额外拒绝符号/硬链接与超限文件 |
| 网络 | SDK network 留空，实际 `NetworkMode=none`；测试地址连接失败 | 必须显式维持生产禁网策略，不能将其他版本默认值视为保证 |
| 资源 | 64 MiB 内存、0.5 CPU、32 PID；CPU throttled 计数增长；子进程创建到上限被拒；分配内存触发 exit 137，`oom_kill=1` | 有实际限制触发证据，不只是 inspect 配置 |
| 上传兼容性 | 官方 `uploadFile()` 的 docker cp 在只读 rootfs + tmpfs 下失败：`container rootfs is marked read-only` | 改用官方 `hydrateWorkspace`/`persistWorkspace` 归档传输，保留只读约束；失败已成为复跑断言 |
| 正常完成 | 生成并发布正式版本，shutdown 后容器消失，tmpfs 随容器销毁 | 业务版本持久于主应用证据目录，工作文件只留在临时容器 |
| SDK stop | SDK `isRunning=false`，但容器内父子心跳继续增加 | stop 不能当取消/进程停止使用 |
| Java 调用取消 | `Future.cancel(true)` 后父子心跳继续增加 | 线程/调用取消不等于容器内终止；没有把本次观察写成完整 ReAct 订阅链验证 |
| SDK 执行超时 | 1 秒 timeout 抛出异常后父子进程仍在运行，心跳继续增加 | 超时需要显式销毁本任务容器 |
| 显式 shutdown | 取消后拒绝迟到发布，再 shutdown，容器实际移除；单次观测 83 ms。超时后也成功移除 | 需要将业务终态与容器清理关联，并检查实际删除结果；时间是单次样本 |
| 主应用 SIGKILL | 主容器 exit 137，任务容器与父子进程仍运行，心跳继续增加 | 不可依赖 finally/JVM shutdown hook 保证崩溃时停止 |
| 重启恢复 | 新主应用按持久记录和本轮标签删除遗留容器，任务标记 INTERRUPTED、不自动重跑，旧版保留 | 本探针验证启动后回收；没有独立看门狗，主应用不重启时残留不会自动清理 |
| 处理与保存失败 | 程序 exit 23 的部分草稿不得发布；注入保存失败保留旧版本；取消与超时后的发布也被拒 | SDK 不承担业务终态与版本权威；生产应使用已确定的 DB 事务边界 |

最终运行 ID `iw8-0006a9bcbf98`，结束后本轮任务容器为 0。初次上传失败以及两次探针自身问题（直接 Toolkit 调用缺少 raw JSON `{}`、命令换行未 trim）的失败输出保存在 [failed-attempts.json](evidence/20261112/failed-attempts.json)，后两项已修正，不作为 SDK 隔离缺陷。

## 接入结论与待决定事项

官方 DockerSandbox 可以复用，无须把业务权威搬入 Harness，也无须开放通用 shell。接入至少需要：每任务独立容器、受控归档传输、明确资源和权限限制、取消/超时显式 shutdown 与结果核验、持久任务到容器关联、启动回收、拒绝迟到发布。

**不能按“配置官方沙箱即可满足边界”放行 #6。** 当前验证路径将普通 Docker daemon 的完整管理权交给主应用。处理容器没有该接口，但主应用一旦被攻破，可能影响同一引擎上的其他容器以及 daemon 可访问的主机文件。macOS 实测作用于 OrbStack Linux 引擎；目标 Linux 服务器上的实际影响要按部署配置重新验收。

提交用户讨论的选择（均未成为新决议）：

1. **建议增加受限沙箱管理服务**：主应用只提交授权资料与固定操作，管理服务持有 Docker 权限；仅接受预置镜像、固定命令、限额、任务身份与清理操作，不接受任意 Docker 参数、宿主路径或命令。代价是增加一个 Compose 组件及管理协议，必须验证授权与故障恢复。这是新建议，没有在本探针实现或验收。
2. 保持主应用直连 Docker socket：部署更简单，明确接受主应用的引擎级权限，补齐本报告指出的生命周期管理。主应用 read-only、禁网或 socket `:ro` 挂载不能使 Docker API 变成受限业务 API。

另可评估专用 rootless daemon 或独立执行主机以降低 daemon 影响范围，但本轮没有验证，不将其作为现成解决方案。只有用户决定权限/部署取舍后，才能更新相应 ADR 并继续判断 #8/#6 收口。

## 证据边界

没有真实模型请求、生产身份系统、Spring Boot 全应用、真实 Office/PDF 解析器、MySQL 版本提交、备份恢复、断电恢复、实际 Linux 生产服务器、daemon 失联/删除失败重试、独立看门狗或恶意内核逃逸测试。固定程序只验证工具到容器的接入与失败机制，不证明未来解析器无漏洞。业务版本保护为单 JVM 原型断言；正式 DB/文件一致性仍按架构方案实施。临时证据保留是有意行为，不是工作区清理遗漏。

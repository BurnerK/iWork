# #21 清理故障与独立监督契约

沿用固定任务处理服务与独立看门狗，不新增通用调度。管理服务在调用删除前持久写入 cleanup=UNKNOWN，真实查询容器不存在后才写 CONFIRMED。删除拒绝或管理服务到 Docker 通信失败保留待重试，500ms循环继续尝试；已有非运行任务仍清理未知时拒绝新处理。管理服务重启沿用 registry，本实例存量资源先回收，确认后才重新接纳。

业务任务取消/中断、本地worker停止、供应商执行状态、容器清理分别记录。worker STOPPED只表明本地执行结束；cleanup UNKNOWN不能表述成容器停止。故障解除后可确认清理，但业务取消/中断不恢复为运行，也不发布迟到成果。原始资料与正式版本不属于管理服务清理目录。

独立看门狗每500ms扫描本实例标签，按 Docker Created 原始时间计算固定60秒期限；不读取任务工作区期限。健康记录在完整扫描成功后刷新，有效期2秒；记录过期/不可读时管理服务拒绝新增。恢复监督不重置存活容器期限，已逾期资源立即进入回收。期限判断仍依赖监督进程、Docker、宿主和时钟可用，不承诺全宿主故障时的墙钟上限。

实际过期删除成功后输出一条 JSON 回执：event=sandbox_expired_removed、instance、container、createdAt、expiredAt、confirmedAt（UTC epoch毫秒）。expiredAt是本轮发现到期时间，confirmedAt是在独立查询确认容器不存在之后采样；不以 docker rm 退出代替确认。回执不含资料、正文或凭据。删除失败不产生成功回执，健康记录按现有规则撤销。

## 专用故障验证

`sandbox/verify_supervision.py` 只连接本机18092，且要求 Compose 名为 iwork-issue21-check。由 launcher exec 启动测试源集 CancellationFixtureApplication，密码来自 IWORK_ACCEPTANCE_PASSWORD。参数依次为 launcher、模型ID、来源版本ID、Compose文件、env文件、证据目录。该目录需含 faults 子目录。

测试管理镜像将原 docker CLI移到docker-real，并复用 prototypes/sandbox-validation/fault-docker.sh 改为 exec docker-real：faults/deny-cleanup 使真实管理进程的rm/stop调用返回失败；faults/daemon-offline使该管理进程所有Docker调用失败。只在专用管理镜像与只读故障目录使用，看门狗仍调用真实CLI。不关闭共享daemon，不将删除伪装为成功。

测试解析镜像基于正式解析镜像，父子进程各等待180秒，以便观察60秒外部期限。保持生产隔离参数和固定解析入口；只有临时测试镜像增加等待。管理服务与看门狗分别拥有独立于其他票的registry/health/key，实例标签issue21；对照容器使用issue21-control，脚本finally只删除其自身对照并恢复本票服务。

正式镜像不含延迟解析器、CLI故障包装或HTTP故障控制。可复核结果见 [验收记录](validation/issue21-supervision.md)。

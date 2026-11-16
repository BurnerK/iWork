# #20 重启恢复契约

主应用仍为单实例，不增加自动续跑、选主或后台工具重放。启动恢复顺序明确为：TaskService 将 RUNNING 任务归档为 INTERRUPTED/UNKNOWN 并清理已登记文档工作区；ModelCallLedger 幂等关闭 OPEN 调用、按已有证据结算并释放预占；MaterialParsing 启动清理状态核对循环。全部 ApplicationRunner 完成、Spring 就绪状态开放后，业务 `/api/` 才接受请求；此前返回503 service_unavailable。静态页和健康探针仍可访问。恢复失败则应用不就绪。

旧 requestId 与原参数仍返回原任务，不触发执行。新请求需通过当前身份、额度、模型授权、会话互斥与容量准入。重复启动只处理未归档任务和未关闭调用，不重复收费或发布。UNKNOWN 表示无法证明远端已停止；不以 JVM 重启推断供应商停止。

摘要/快照属于 MySQL 业务备份。只有来源任务已完成且 owner、会话、历史哈希、水位和快照内容通过校验的记录才可恢复。强杀后候选快照不会使旧任务或工具复活。显式新任务按成功历史恢复或重建，无法确认时失败。

成果沿用先完整文件、后数据库事务的顺序。文件已完成但索引未提交时，允许留下完整未引用文件，API 不可见。不得启动时按文件存在自动发布，也不得盲删文件；离线清理见后续 #21。既有正式版本和原始资料保持不变。

管理服务启动先处理本实例持久 registry 与实例标签容器，实际 Docker 不存在后才确认回收。独立看门狗的条件性上限不变。不同实例容器不删除；Docker/宿主不可用时不得声称已停止。本票验证 SIGKILL 与管理服务重启，不等同断电、磁盘缓存丢失或目标 Linux 灾难恢复；后者在 #25 说明与验收。

## 故障验收运行

`scripts/verify-recovery.py` 只连接本机18092，强杀它自己启动的进程；要求专用 `iwork-issue20-check` Compose、已确认标签的 `iwork-issue12-check-mysql-1` 测试数据库和测试夹具 launcher。密码来自 IWORK_ACCEPTANCE_PASSWORD / IWORK_ACCEPTANCE_DB_PASSWORD 环境变量，不写报告。它会保留测试会话/版本作为证据，不用于生产。

launcher 必须 exec Java，以 test classpath 启动 CancellationFixtureApplication，绑定127.0.0.1:18092，配置隔离数据库/Redis/文件目录和 issue20 管理入口；外部模型传输由确定性替身替代。脚本的模型与来源版本参数必须是该测试库已有、当前用户可用的记录。测试解析镜像从正式镜像派生，只在读取前启动 Python 子进程并让父子各等待50秒，保留生产隔离参数；不修改正式解析器。Compose 独立 registry/watchdog/key，instance=issue20，模型窗口16384。

命令参数：`python3 scripts/verify-recovery.py LAUNCHER MODEL_ID BASE_VERSION_ID COMPOSE_FILE ENV_FILE EVIDENCE_DIRECTORY --files-directory FILES_DIRECTORY`。

脚本用独立数据库连接持有任务/成果行锁，分别定位启动恢复与正式提交边界；只终止自己创建的锁连接。预期 MySQL 客户端报告连接被终止。finally 恢复本票管理服务并删除自己的对照容器。临时构建输入与运行日志存放 tmp/codex/issue20。

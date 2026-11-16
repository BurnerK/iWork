# #24 诊断验证

2026-11-17，本地隔离 MySQL/Redis/文件、真实 Harness 2.0.4 和 Docker 处理容器；模型使用 transport 替身，无真实供应商费用。

## 业务 API

- `ModelExecutionTest` 首次请求聚合接口观察 404，接入后 200。实际模型异常与部分 usage 可定位 model_failed/PARTIAL_ESTIMATE/供应商UNKNOWN，MAIN/SUMMARY/OVERFLOW 分开；诊断与原账本金额字符串完全相等。捕获日志不含私有异常标记或合成模型密钥。
- `ConversationContextTest` 实际压缩、摘要错误/空/过长回退和损坏快照，诊断显示 COMPACTED/FALLBACK/SNAPSHOT_REJECTED，不返回蓝鲸项目正文或摘要。
- `MaterialParsingTest` 无效 UTF-8 经过真实处理容器失败，返回 material_parse_failed、清理尝试时间和确认状态。
- `PersonalAgentTest` 普通用户跨 owner 404、管理员普通私有入口仍 404、普通用户管理入口 403；管理诊断只返回运行投影，与 owner 诊断一致，不含文件名、正文、指令、令牌或标题。分页越界 400。
- 前端覆盖业务取消后供应商未知/清理待重试独立展示，以及清理确认后供应商未知仍保留。

## 真实故障与浏览器

专用 `iwork-issue21-check` 管理器 CLI 清理拒绝（仅测试镜像），任务 3792bdc7-4ae3-49e0-8083-747db98bc401、处理作业 6aabdca2-5537-4f15-8b8a-6fecb3f3fa17。取消后任务本地STOPPED、清理UNKNOWN且尝试至少2次，独立 `docker ps` 仍看到容器 a7fa045d740f。管理员页面显示业务已取消、处理停止未知和清理待重试，不冒称全部停止。[页面截图](issue24-cleanup-unknown.png)已查看。

撤销故障后，自动重试于04:37:31确认清理，累计16次；页面刷新为已确认，独立 Docker ps -a 无该作业。模型响应完成和缺失 usage 仍分开显示。临时证据在 `/tmp/codex/issue24/cleanup-unknown.json`、`cleanup-confirmed.json`，故障开关已移除。

浏览器实际模型错误任务 85472b82-00e6-4c9b-8f9c-bda1fd89a460 显示 model_failed、供应商执行未知、LIMIT_ESTIMATE、预占释放；[截图](issue24-model-error.png)已查看。无效资料任务 a3d2e66c-0e21-4842-aa6b-edfa607a44cb 显示解析失败 material_parse_failed、处理作业3b1b4432-db6e-448d-bbd3-3925fc543b61、清理确认时间；调用错误与处理错误均可定位。应用日志未出现替身私有异常标记。

边界：这是正式应用本地故障样本及受限诊断，不证明目标服务器时限或真实供应商停止；#25/#26仍单独验收。

首次完整回归26项中1项智能体资料任务失败：沙箱注册记录只有取消墓碑，无 containerId/requestHash，未创建处理容器；当时另有浏览器故障验收服务运行。停止浏览器服务后该用例及模型用量用例定向复跑通过。现有证据只确认调用未获可用沙箱，不把具体原因推断为CPU或看门狗故障，也不加入自动重试掩盖失败。

最终独立完整 `-Pdistribution clean verify`：26项测试全部通过，0 failure/error/skipped，进程退出0。前端26项测试、typecheck、build通过。两轴评审提出金额JSON数值可能丢精度，已改成十进制字符串并通过与原账本一致性断言，复查关闭；无剩余评审阻塞。

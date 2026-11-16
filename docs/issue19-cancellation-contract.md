# #19 取消与迟到结果契约

沿用既有状态与版本边界。取消先锁定任务并写入 CANCELLED / STOP_REQUESTED，再中断本地执行；完成事务先提交则保持 COMPLETED。worker 结束只说明本地执行结束，不代表供应商停止或处理容器已清理。容器状态与 cleanup 由管理服务观察/对账单独记录。

正常结果和测试迟到查询结果复用 TaskService.acceptResult；此包内业务方法没有新增 HTTP 产品入口。正式保存仍先验证 RUNNING、准备完整文件，再在任务锁和身份校验下发布版本/回复/完成状态。取消、失败或中断不能靠迟到成功恢复执行。重复结果不产生新版本。

CancellationClosureTest 的控制器、HTTP 回执服务、门控与提供方均仅在 test 源集。测试在草稿完成后取消，再发起两次新的 HTTP 查询取得远端 COMPLETED 回执，逐次经过相同 acceptResult 被409拒绝。另验证主模型响应前/输出中、统一 SUMMARY/OVERFLOW 入口中的取消、提交竞争与已完成后的取消。正式 Harness 的自动溢出重放仍关闭；OVERFLOW 取消测试验证统一入口，不声称开启自动恢复。真正的自动摘要取消由 #27 的业务测试覆盖。

用量按 #14 账本保留已观察或估算信息，未知不写成零；取消后关闭调用并释放预占。SDK 返回、docker 客户端退出不作为清理证据。实际容器不存在由 Docker 查询确认；管理服务不可达时保持 UNKNOWN，恢复后继续对账。

真实容器脚本 sandbox/verify_cancellation.py 只允许 localhost 与名为 iwork-issue19-check 的可丢弃栈；需要测试入口、固定50秒延迟解析镜像和独立 registry/watchdog/key。普通运行使用原解析镜像，延迟和控制 API 不进入产品。`--pause-for-browser` 仅暂停测试脚本让自动化截图，回车后恢复测试管理服务；异常退出也在 finally 中恢复。

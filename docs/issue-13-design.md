# #13 后台任务与持久观察

## 行为与存储

任务和消息分别存入 tasks 和 messages。业务 API 沿用真实 Session/CSRF，读、取消、提交都检查会话或任务 owner；管理员没有内容绕过权。

通过 `POST /api/conversations/{id}/ag-ui` 提交一条用户消息。runId 用作 requestId，由 1–100 个 ASCII 字母、数字、下划线或连字符组成。消息正文非空，最多 20000 个 UTF-16 字符。模型、智能体、资料和输出选项在 forwardedProps 中传递。HTTP 200 事件流中的 `iwork.task` 回执包含任务 ID 和状态。相同用户和请求编号重试原参数时返回原任务，改变会话、模型或智能体、正文、资料顺序、输出模式或来源版本时返回 409。

事务内锁定当前身份和会话，数据库生成列唯一约束保证每会话最多一个 RUNNING。跨会话容量默认 2，可用 `iwork.task-capacity` 设为 1–16；本机执行许可一直保留到执行及状态收尾结束，不因业务取消提前释放。同一会话已有活动或排队任务时，新请求进入待发送队列。申请执行许可时容量已满，或待发送队列已达上限时，返回 429。此容量机制仅针对已确认单实例。

执行通过 TaskRunner 接缝由服务端虚拟线程托管，模型/工具运行在数据库事务外。正式运行器由 #14 接入；没有运行器返回 503 execution_unavailable，不创建虚假成功任务。只有测试类路径的 TaskFixtureApplication 提供可控替身，正式 jar 不包含它。

## 状态与恢复

业务状态 QUEUED/PAUSED/REMOVED/RUNNING/COMPLETED/FAILED/CANCELLED/INTERRUPTED 与本地执行状态 WAITING/RUNNING/STOP_REQUESTED/STOPPED/UNKNOWN 分开。STOPPED 只表示本地运行器返回，不证明供应商停止计费或容器已删除；后续组合状态由相应票接入。

取消与完成锁定同一任务行；取消先成立不再发布助手回复，完成先成立后取消保留完成事实。取消发出线程中断，但业务终态不依赖线程立即停止。执行失败返回通用错误，不泄露供应商异常正文。

结果持久化失败时只重试保存失败/停止状态，不重新执行模型。数据库不可用期间保留容量，恢复后归档；进程退出时不无限等待，启动将遗留 RUNNING 归档为 INTERRUPTED，并将未确认执行记为 UNKNOWN。旧请求返回原中断记录，绝不自动续跑。

## 页面与查询

- `GET /api/tasks/{id}` 查询单项；`POST /api/tasks/{id}/cancel` 传 `{}`。
- `GET /api/conversations/{id}/tasks?page=0` 按新到旧返回任务历史，每页 20 项，同时按发送顺序返回全部待发送项。
- `GET /api/conversations/{id}/messages?after=0` 按消息游标读取，每页100。
- 页面通过 `GET /api/tasks/{id}/ag-ui` 恢复对同一任务的观察。关闭页面后任务继续执行。
- 未确认请求按实际用户/会话暂存浏览器本地存储，重开先恢复原请求和要求；确认收到任务后清除。其他登录用户不会加载该草稿。后端仍独立校验全部授权和幂等，不信任本地数据。浏览器本地存储禁止写入时不发起不能保留重试身份的新请求。

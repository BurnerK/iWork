# #19 取消与迟到结果验收

2026-11-17。本地真实 MySQL/Redis/文件与 Harness，模型传输为确定性替身。生产提交代码只抽出包内 acceptResult 复用边界；没有新增人工批准或故障 HTTP 产品入口。

## API 与竞争

CancellationClosureTest 通过真实登录/会话/任务 API 验证：

- 主响应前、输出中、SUMMARY/OVERFLOW 统一入口中的取消，实际 Flux 订阅收到 cancel；供应商用量未知仍为空，费用估算大于零，预占归零。
- 文档准备完成后取消。测试回执服务两次接受新的 HTTP 查询并回报成功；两次结果都经过正常 acceptResult 提交方法，均409拒绝，正式版本数量和旧版字节不变，候选快照不激活。
- 六轮同时释放完成门控与取消请求，终态只可能 COMPLETED/CANCELLED，对应版本数量分别为1/0。
- 完成后再取消保持 COMPLETED。

#17/#18 已有真实保存失败测试继续覆盖旧版本保护；#27 的实际自动摘要取消测试继续覆盖不完整摘要不覆盖可用背景。正式自动溢出重放仍关闭，OVERFLOW 场景验证统一辅助调用入口，不宣称启用自动重试。

## 真实处理容器与页面

独立 Compose `iwork-issue19-check`，instance=issue19，管理入口仅测试发布127.0.0.1:18094；与既有 issue16 验收栈分离。固定测试解析镜像 `iwork-text-parser-cancellation:19` 基于正式解析镜像，只在 parse.py 读输入前加入 time.sleep(50)，保留相同 entrypoint/非root/只读/无网络/资源限制。故障延迟不进入正式镜像。

`sandbox/verify_cancellation.py` 通过应用18092的真实业务入口提交并取消两个带原始资料、基于旧版本的任务。登录密码仅由 IWORK_ACCEPTANCE_PASSWORD 环境变量读取，脚本不输出密码。脚本仅接受localhost、指定测试栈，异常时恢复暂停的管理服务。

会话 `c3cc1614-d69b-4d12-8b46-f3cf38a8aba7`：

1. 正常取消任务 `5c4fefc5-c94a-4ab2-a1d1-6910cbe17cdb`，从 Docker 观察运行容器到取消后不存在，cleanup=CONFIRMED。
2. 任务 `3f682e7f-34ff-4eec-8910-176240e41c0f` 的处理容器启动后暂停管理服务。业务取消与本地停止成立，解析记录 `39adf445-a2d9-42a1-8a3e-ed32d897004f` 的 cleanup=UNKNOWN；同次观察 Docker State.Running=true。页面明确显示清理未确认，未假称已清理。
3. 恢复管理服务，对账后 cleanup=CONFIRMED，Docker按job标签查询无容器。页面转为已确认清理。原版本2d8e68b5-938d-437c-a5e3-05cdd77577dd字节保持不变，没有取消任务的正式新版本。
4. 两条任务调用关闭、预占归零；未知供应商用量保留，页面显示其中一条估算0.003918 CNY。

issue19-unknown.png 与 issue19-confirmed.png 均已查看。测试临时目录 /tmp/codex/issue19 保存 Compose、延迟镜像构建输入和 docker-evidence.json；故障栈已恢复，没有暂停中的管理服务。

## 最终检查

最终后端 clean verify 共24项，0失败、0错误、0跳过；前端21测试、typecheck/build通过。正式JAR内容检查无Fixture/测试回执控制器；启动正式JAR后，已登录请求测试控制路由返回404。两轴只读复核未发现本轮阻塞项。

未连接真实供应商或目标Linux。供应商最终费用/停止仍未知，不以本地测试替身冒充真实供应商证据。

# #14 Harness 与逐调用计量验收

SDK：正式工程引入 `agentscope-harness`、`agentscope-extensions-model-openai` 与 core，均为 2.0.4；沿用 Boot/Jackson/MCP 安全版本约束。运行规则见 [计量契约](../issue-14-design.md)。

业务 API 测试使用真实身份、MySQL、Redis、Harness 和 OpenAI 适配器，仅替换供应商 HttpTransport。检查实际请求没有工具 schema，输出上限 2048 且请求 usage。覆盖主调用、SUMMARY/OVERFLOW 共用治理入口、重复/零/部分/缺失/迟到回报、保留错误前用量、无输出保守估算、总尝试上限、取消实际订阅、取消后辅助调用拒绝、跨会话额度竞争和撤权后的辅助调用拒绝。首版生产压缩关闭，测试辅助调用不意味着 #27 已交付。

两个缺陷有红绿证据：

- 16 路并发收尾且另有预占时，旧 RR 快照使预算约束失败；改为当前加锁读后，仅结算一次，另一笔预占不变。
- MySQL 暂停期间到达 usage，旧实现丢弃回报并失败；幂等持久化重试后恢复完成，保留供应商用量，不重发模型调用。字节观察改为累计最大值，避免提交结果不确定时重复累计。

安全与兼容：OSV 查询当前 136 个运行时 Maven 构件，未检出已知漏洞。此结果针对当前依赖树及查询时点，不沿用旧 core-only 结论；未完成真实供应商兼容与外部 Linux 标定。正式 JAR 不包含测试供应商入口。

规范/规格双轴审查发现并修复并发结算与零用量丢弃；复核通过。额外区分供应商完成前累计回报与最终回报，前者保持待核实。完整后端 20 项、前端 16 项及类型检查通过；追加静态技能拒绝检查后再次通过 ModelExecutionTest 与分发构建。生产 JAR 检查未包含 Fixture/Test 类。

浏览器以合成管理员与传输替身验证正式 Harness：任务 `d28ece6e-9330-483f-af02-b4a9a47cc440` 完成，逐调用页面显示供应商 100/20 tokens、内部金额 0.000175 CNY、预占释放。键盘 Enter 可打开用量，390px 无横向溢出。[桌面](issue-14-desktop.png)、[窄屏](issue-14-mobile.png)。

强制终止独立验收 JVM 后用正式 JAR 重启：任务 `e7d41572-ae47-4476-85f6-12a844bb004e` 与 `fe4d903a-de88-4496-801a-5bb85a5c9d16` 均为 INTERRUPTED/UNKNOWN，调用条数仍各 1，无自动重放。前者保留完成前的 100/20 累计回报，标记 PARTIAL_ESTIMATE；后者无供应商 token，标记 OBSERVED_ESTIMATE。预占从 0.012701 变为 0，累计内部消耗从 0.000175 调整为 0.001612 CNY。[重启后的待核实展示](issue-14-restart.png)。

源码另查明关闭动态技能仍可能装配静态工作区仓库：正式入口拒绝非空技能仓库；放入合成 SKILL.md 后任务失败关闭，没有供应商请求。

官方依据：[Harness 2.0.4](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java)、[core ReActAgent](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java)。`maxRetries(1)` 实际映射为总尝试数 1；`wait_async_results` 即使关闭子智能体仍默认注册，正式构建显式移除并断言空工具集合。

限制：本地取消/重启不证明供应商停止或结清；未知消耗以明确来源估算内部额度并释放预占。数据库不可写且进程被强制终止时，未持久化观察只能保持待核实。没有真实模型调用、生产部署或完整供应商账单对账。

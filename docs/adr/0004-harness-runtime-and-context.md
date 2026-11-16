# 首版采用 HarnessAgent 管理运行上下文

状态：2026-11-16 用户已确认，实施与验收待完成。关联 [首版规格 #9](https://github.com/BurnerK/iWork/issues/9)。本决定细化 ADR 0001 的 SDK 运行机制，沿用 ADR 0002 的部署和 ADR 0003 的逐调用额度规则。

## 决定与原因

首版直接采用 AgentScope Java HarnessAgent，交付会话上下文恢复和自动压缩。用户已明确最终采用 Harness；在正式运行模块尚未实现时接入，避免先建设 ReAct 专用机制再迁移。SDK 接入基线为 2.0.4，相关模块同版本；主应用仍是模块化单体，不增加 AgentScope Service 或新的运行服务。保留 #11 已批准的 Redis 登录态修订；Redis 不用于 Harness 快照、摘要或业务队列。

ReActAgent 本身可执行工具并持久化 AgentState，并非仅能一次性问答。选择 Harness 是为了复用上下文管理的组合能力，不表示推理质量天然提升，也不把已有 ReAct 验证等同于 Harness 验证。

## 能力和数据边界

- 显式启用上下文持久化和自动压缩，只开放平台固定、已授权业务工具；关闭通用文件/shell/Web 工具、长期记忆、动态 Skill/MCP、子智能体和 Plan Mode。关闭独立会话日志、压缩前记忆写入和日志卸载，以及需要通用读文件工具的大结果卸载；业务工具有界读取资料。
- 完整业务消息、运行上下文快照及会话摘要均保存在 MySQL，后两者按实际用户/会话关联并能追溯来源任务。原始资料、执行工作文件和正式成果分别管理；SDK 默认用户目录不承担持久化职责。精确存储结构与适配在开发票中落实。
- iWork 持有任务、幂等、互斥、权限、额度和成果版本权威；SDK 状态只用于模型执行与后续显式新任务的上下文。快照不能带回旧授权或覆盖新任务配置，不允许恢复未完成工具、自动重放或自动续跑中断任务。
- 压缩不删除完整历史或成果。失败时保留原历史和可用快照；原上下文在预算内可继续，否则明确失败。不一致快照依据业务记录核对重建，无法确认则失败。
- 所有主推理、摘要、溢出恢复和重试都进入同一模型授权与额度边界，关联当前用户/任务并遵守取消、超时和总尝试上限；不能仅依赖主推理事件统计辅助调用。任务终态后不启动后台记忆维护调用。
- 沙箱管理服务及独立看门狗不变。Harness 仍通过固定业务工具请求受控处理，不直连 Docker，不把通用沙箱示例作为生产配置。

## 实施与验收

#14 交付 Harness 调用入口、依赖兼容性、工具限制和统一模型调用治理；[#27 长会话恢复与自动压缩](https://github.com/BurnerK/iWork/issues/27)交付 MySQL 快照、恢复和自动压缩。#18、#20 等待上下文任务，验证文档续改与重启组合行为。#25 把快照和摘要纳入协调备份恢复；#26 验证真实模型压缩后的连续工作质量。

测试入口沿用业务 API、少量浏览器流程和真实 Compose/Docker 故障验证，补充 T17–T19；只测试可观察行为，不依赖内部类的装配顺序。旧探针保留原版本和结果，作为取消、用量、隔离、迟到发布等场景先例。

## 代价与证据限制

Harness 默认能力需要逐项限制，摘要调用增加费用和失败路径，快照必须与业务状态协调。2.0.4 压缩文档与 builder 源码的默认启用描述存在差异，具体行为以锁定版本源码及测试核查；不根据方法名推断开关有效。

当前正式工程仅引入 core，既有兼容测试和安全扫描结论不覆盖新增 Harness 依赖树。本次只确认方案，不声明 Harness 集成、摘要质量或生产恢复已通过。

参考：[v2.0.4 Harness 架构](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/architecture.md)、[上下文压缩](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/compaction.md)、[HarnessAgent 源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java)。

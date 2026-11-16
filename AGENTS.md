## Agent skills

### Issue tracker

本项目使用 GitHub Issues。见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认的五个任务分类标签。见 `docs/agents/triage-labels.md`。

### Domain docs

采用单一领域文档结构。见 `docs/agents/domain.md`。

### AgentScope Java

涉及 AgentScope 实现、调试或升级时，先读 `backend/pom.xml` 确认实际依赖，再读 `docs/adr/0004-harness-runtime-and-context.md`。查阅对应版本的[官方中文文档](https://github.com/agentscope-ai/agentscope-java/tree/v2.0.4/docs/v2/zh/docs)和[源码](https://github.com/agentscope-ai/agentscope-java/tree/v2.0.4)；这些链接是当前 2.0.4 基线，升级时同步更新。文档与源码不一致时核对锁定构件并测试，不根据默认示例扩展工具、授权或恢复边界。目标方案使用 HarnessAgent，不表示当前依赖已完成接入。

## Design System

本项目使用 DESIGN.md 格式来定义视觉规范。
根目录下的 DESIGN.md 文件是所有 UI 决策的唯一依据。

### Mandatory instructions for UI

1. BEFORE generating any visual component, read DESIGN.md completely
2. Use EXCLUSIVELY the tokens defined in the YAML front matter
3. Follow the typographic scale without inventing intermediate sizes
4. Spacing must be multiples of the base value
5. Every interactive element needs: hover, focus, disabled
6. The "Constraints" section of DESIGN.md is inviolable

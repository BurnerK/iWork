## Agent skills

### Issue tracker

本项目使用 GitHub Issues。见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认的五个任务分类标签。见 `docs/agents/triage-labels.md`。

### Domain docs

采用单一领域文档结构。见 `docs/agents/domain.md`。

## Design System

本项目使用 DESIGN.md 格式来定义视觉规范。根目录下的 DESIGN.md 文件是所有 UI 决策的唯一依据。

### Mandatory instructions for UI

1. BEFORE generating any visual component, read DESIGN.md completely
2. Use EXCLUSIVELY the tokens defined in the YAML front matter
3. Follow the typographic scale without inventing intermediate sizes
4. Use the spacing tokens and documented alignment exceptions in DESIGN.md
5. Every interactive element needs: hover, focus, disabled
6. The "Do's and Don'ts" section of DESIGN.md is inviolable

## TypeScript/JavaScript 测试

## E2E 测试

使用 **Playwright** 作为关键用户流程的 E2E 测试框架。

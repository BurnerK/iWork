# 前端组件开发规范

## 组件开发

- 在 [`frontend/src/`](../frontend/src/) 编写 Vue 单文件组件，沿用 `<script setup lang="ts">`。组件名用 PascalCase，业务面板按功能命名，例如 `MaterialPanel.vue`。
- 用 `defineProps<T>()` 声明输入，用 `defineEmits<T>()` 声明事件及其参数。父组件持有的值通过事件更新，例如资料面板通过 `update:modelValue` 回传选中的资料 ID。仅供组件使用的加载、错误和展开状态留在组件内。[资料面板](../frontend/src/MaterialPanel.vue)
- 有多个调用方需要同一种行为时，再提取共享组件。前端的 Markdown 渲染已有 `MarkdownContent.vue`，会话回复和文档预览可以沿用该入口。[Markdown 组件](../frontend/src/MarkdownContent.vue)
- 普通业务请求使用 `api.ts` 的 `request` 和 `errorMessage`。接口响应结构集中在 `apiSchemas.ts`。任务提交和流式回复沿用现有 AG-UI 入口。组件发起异步操作时，按场景呈现加载、空结果和错误，并在写操作进行时防止重复提交。[请求封装](../frontend/src/api.ts) · [响应校验](../frontend/src/apiSchemas.ts) · [任务提交](../frontend/src/agUi.ts)

## 样式

- 颜色、字体、字号、间距、圆角、响应式布局和交互状态的视觉规则统一遵循 [`DESIGN.md`](../DESIGN.md)。
- 工作台从 `main.ts` 加载全局 `style.css`。修改组件时优先复用其中对应的 CSS 变量、控件类和状态样式。新增面板专用样式时，用面板类限定选择器的作用范围。需要调整全局控件样式时，检查使用该控件的其他面板。[入口](../frontend/src/main.ts) · [样式表](../frontend/src/style.css)
- 检查可交互控件的悬停、键盘焦点和禁用状态，并在窄屏下确认标签、操作入口和焦点指示仍可见。

## 依赖

- 优先使用原生 HTML 控件、Vue 和项目已有的工具。涉及路由、响应校验或 Markdown 渲染时，分别沿用 Vue Router、Zod 和 `MarkdownContent.vue` 中的 markdown-it 配置。
- 确需新增依赖时，在变更说明中交代它解决的问题和引入范围。运行时依赖放入 `dependencies`，测试和构建工具放入 `devDependencies`，同时更新前端的依赖清单和锁文件。根目录的 npm 配置用于运行项目命令。[前端依赖清单](../frontend/package.json) · [前端锁文件](../frontend/package-lock.json)

## 无障碍

- **使用原生控件。** 执行动作用 `button`，导航或下载用链接。表单里的普通操作按钮显式使用 `type="button"`，提交按钮使用 `type="submit"`。不可用的原生控件使用 `disabled`，让鼠标和键盘操作都遵从同一状态。[项目归属表单](../frontend/src/ProjectMembership.vue)
- **给控件可访问名称。** 输入框和选择框使用关联的 `label`，补充说明可通过 `aria-describedby` 关联。只有图标的按钮使用 `aria-label` 描述实际动作。展开按钮通过 `aria-expanded` 反映当前状态。[登录与改密表单](../frontend/src/App.vue) · [任务面板](../frontend/src/TaskPanel.vue)
- **让键盘可以完成整个操作。** 检查 Tab 顺序、可见焦点以及 Enter 或 Space 是否能触发控件。模态对话框沿用原生 `dialog`，检查打开后的焦点、Escape 关闭和关闭后的焦点归还。现有端到端测试会用 Enter 打开文档预览，并检查关闭后焦点回到原成果卡片。[文档预览](../frontend/src/DocumentPanel.vue) · [工作台测试](../frontend/e2e/workspace.e2e.ts)
- **用文字反馈操作结果。** 用 `role="status"` 呈现进度或成功信息，用 `role="alert"` 呈现需要及时感知的错误。状态变化应有明确的文本提示。流式回复中的工具进度使用独立的 `aria-live="polite"` 区域。[任务面板](../frontend/src/TaskPanel.vue) · [资料面板](../frontend/src/MaterialPanel.vue)
- **保留输入法行为。** 自定义快捷键要考虑中文输入法。消息输入框在组字期间保留 Enter 的选词行为，Shift+Enter 用于换行。[任务面板](../frontend/src/TaskPanel.vue) · [工作台测试](../frontend/e2e/workspace.e2e.ts)

组件行为的回归测试沿用同目录的 `*.test.ts` 和 Vitest。影响导航、提交、预览等关键流程时，用 Playwright 检查用户可见的行为。优先按控件角色和可访问名称定位，并按变更范围验证键盘、焦点和窄屏操作。[前端检查命令](DEVELOPMENT.md#提交前检查)

# #10 生产工程骨架验收

日期：2026-11-16。环境：macOS arm64、OrbStack Docker 29.8.2 / Compose 5.5.0；容器内 Linux、MySQL 8.4.7、Temurin 21.0.13+9、Node 26.13.1 / npm 11.20.0。范围仅为 #10 的运行前置检查，不代表 #25 的目标 Linux 部署、升级或协调恢复。

## 自动化

| 验证 | 结果 |
| --- | --- |
| `npm ci --prefix frontend` | 从 lockfile 安装成功 |
| `npm --prefix frontend run typecheck` | 通过 |
| `npm --prefix frontend test` | 5 项通过：就绪、错误响应、不合法响应、网络失败及重试/禁用状态 |
| `npm --prefix frontend run build` | 通过，生成静态入口和资源 |
| `backend/mvnw -B -f backend/pom.xml -Pdistribution verify` | 编译、4 项真实 MySQL HTTP 测试、交付 jar 打包通过 |
| `python3 scripts/verify-compose.py` | 全部通过；独立项目已清理容器和网络 |

后端 HTTP 测试覆盖无详情就绪响应、诊断及业务入口拒绝、数据库不可达时就绪 DOWN/存活 UP、文件目录不可用时就绪 DOWN。测试使用真实 MySQL，不替换数据库健康检查。

Compose 验证从空持久目录启动；检查 V1 成功记录只有一条；重建应用及数据库容器后迁移记录和文件标记保留；停止 MySQL 后就绪返回 503、存活返回 200；恢复数据库后就绪回到 200。普通日志未包含两项随机测试密码。该脚本使用隔离项目，不停止其他现有服务。

TDD 首轮证据：后端就绪请求最初返回 404，前端最初缺少状态节点，均在对应实现加入后通过。故障测试曾因 Testcontainers 的连接串覆盖应用超时而阻塞，已在测试连接上显式设置连接与 socket 超时后通过。

## 浏览器

使用 ego-browser 访问容器交付入口，确认“服务已就绪”；375px 宽度下文档宽度也是 375px，无横向溢出。键盘可定位按钮，焦点为 DESIGN.md 中的蓝色实线，Enter 可触发连接检查。真实停止 MySQL 后页面显示“服务暂不可用，请稍后重试”，无数据库或异常详情。恢复 MySQL 并等待服务就绪后可重试。

## Standards

独立审查相对实施前提交 `7a9166b2bf78fe209e456d491473062a8b184f90` 的差异，核对 AGENTS.md、完整 DESIGN.md、词汇表及 ADR；0 项可行动发现。未重复运行构建或浏览器检查。

## Spec

独立审查 #10 的完整需求摘要及本地父规格；0 项可行动发现。审查代理未能独立联网读取 Issue，工单原文由主代理读取并提供范围。两轴未发现遗漏或违规，不等于生产验收。

## 已知限制

- GitHub Actions 工作流已建立，本地执行相同基础命令；尚未在远程 Actions 上运行。
- npm audit 报告 5 项中危开发依赖告警，来源是 `@vue/test-utils` 的 `js-beautify` / glob / minimatch 依赖链；`npm audit --omit=dev` 为 0 项。不以强制跨主版本覆盖依赖来掩盖风险，后续跟随测试工具修复升级。测试工具不打入生产页面。
- Flyway 11.7.2 对 MySQL 8.4 输出上游支持提示；本票的真实初始化、迁移重复启动和故障恢复已经通过，未来业务迁移仍需随功能验证。
- 未接真实模型、未实现登录或业务数据、未运行原型测试、未做目标 Linux 升级/备份恢复；这些属于后续工单。

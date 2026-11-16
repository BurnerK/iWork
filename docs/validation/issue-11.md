# #11 身份与个人会话验证

日期：2026-11-16。范围依据 [已批准合同](../issue-11-design.md)。验证使用合成账号、独立测试容器和临时目录，不连接真实业务实例。未发布或关闭 GitHub 工单。

## 自动化证据

| 边界 | 命令 / 结果 | 证据范围 |
| --- | --- | --- |
| 前端 | `npm --prefix frontend test`：8 通过；typecheck、build 通过 | 强制改密路由、安全错误与重试、CSRF 登录/重复提交保护、直接会话链接、临时密码离页清除、账号切换隐私、退出后迟到响应、改密后显式登录 |
| 真实 HTTP + MySQL/Redis | `backend/mvnw -B -f backend/pom.xml -Pdistribution verify`：16 通过，无跳过 | 9 个身份/会话流程、4 个既有应用健康测试、1 个 AgentScope 与 2 个 MCP 兼容测试 |
| 隔离 Compose | `python3 scripts/verify-compose.py`：通过，项目 `iwork-smoke-hhemyovh` | 迁移、bootstrap 幂等、应用及 Redis 重建、登录与会话保留、依赖故障、Redis 丢失、恢复命令 |

身份测试没有 mock 认证、数据库或 Redis。使用 Java HttpClient 的真实 Cookie/HTTP、Testcontainers MySQL 8.4.7 / Redis 8.2.3。仅时间边界注入可控 Clock，实际 Redis Session 仍用于所有请求。

最终 Compose 用完整 Dockerfile 从源码构建镜像，验证原始初始密码在重建后不能覆盖已修改密码；登录 Cookie 可继续读取原会话；MySQL/Redis 中断均使受保护请求及 readiness 返回503而 liveness200；Redis FLUSHDB 后重新登录仍能打开原会话。管理员恢复先拒绝0644秘密文件且旧登录仍有效，再接受0600文件、撤销旧登录并强制首改。日志检查不含本次生成的数据库/初始/新设/恢复密码。测试容器、网络和Redis卷已删除。

本机原始日志：`/tmp/codex/issue11-final-frontend.log`、`/tmp/codex/issue11-final-backend.log`、`/tmp/codex/issue11-final-compose.log`。这些是本次运行的临时证据路径，不作为仓库运行依赖。

覆盖首次改密前禁止业务访问；原密码不能清除强制改密；改密返回 204 并撤销当前/其他设备登录；登录 Session ID 轮换；Cookie 的 HttpOnly / SameSite / 30天 Max-Age；登录/写入/退出 CSRF；5次连续失败锁定15分钟及成功清零；闲置7天与持续访问下绝对30天；创建/重置密码、禁用后再启用不复活旧登录；用户角色边界与管理员无 owner 绕过；伪造 owner/role 拒绝；分页、72 UTF-8字节密码接受、超限拒绝；MySQL/Redis 暂停时受保护请求503、公开 liveness仍200。

TDD 先确认原骨架 `/api/auth/me` 403 与期望401的红灯，再实现登录；随后分别添加改密/会话、用户管理切片并由红转绿。故障测试暴露了即使公开安全链设为 stateless，框架仍可能通过 Session 详情读取 Redis；现只对 API 解析登录 Cookie，公开探针及静态入口独立。审阅增加的“同密码首改”与“账号A失效→账号B登录→列表503”均先复现红灯再修复；迟到响应也有回归。

## 真实浏览器

通过 ego-browser 的 Chromium 在回环 Compose 实例完成，使用与最终代码同源的分发 jar（只读挂载至标准交付镜像的 `/app/iwork.jar`）。步骤全部通过 UI 操作，不以 API 调用代替页面流程：

1. 初始管理员登录，强制改密页刷新仍可用；改密后回到登录，使用新密码重新登录。
2. 管理员创建普通用户，临时密码一次显示；隐藏后退出，以普通用户登录并首改，再次显式登录。
3. 普通用户新建会话，空详情显示“暂时没有消息”；刷新直接链接仍能打开相同会话，没有管理入口或消息输入框。
4. 管理员重置该用户，旧密码登录被拒绝；新临时密码登录仍要求改密。
5. 管理员禁用用户，正确临时密码也无法登录。
6. Enter 提交登录；Tab 到“新建会话”时焦点轮廓可见；390px viewport 的 scrollWidth=390，无横向溢出。

[桌面会话截图](issue-11/conversation-desktop.png)、[390px会话截图](issue-11/conversation-mobile.png)、[禁用用户截图](issue-11/admin-disabled.png)。截图均为合成内容，无密码。浏览器任务空间和验收实例已清理。

## 证据限制

本机 macOS + OrbStack Linux 容器验证不替代生产 Linux、HTTPS反向代理、真实网络/负载、断电恢复、数据库与文件协调备份恢复或多浏览器/屏幕阅读器验收。7/30天由可控时间测试，不是等待实际天数；跨浏览器重启能力依据持久 Cookie 和服务端 Session 配置，未重启整台浏览器进程。恢复命令已通过受保护文件执行，交互终端隐藏输入路径尚未实机执行。不兼容 Session 升级允许重新登录；AOF everysec 不承诺断电零丢失。没有模型、消息、任务、项目空间功能。

## 使用的指导

使用 TDD 的已批准真实 HTTP、页面与部署命令边界；前端应用 modern-web-guidance 的 forms、autofill-sign-in-form 与 accessibility：原生表单和必填约束、密码自动填充、操作期间禁用重复提交、可见标签和错误、路由标题/焦点、键盘操作。Spring Session 配置依据[官方 Cookie 指导](https://docs.spring.io/spring-session/reference/configuration/common.html)与[Redis Session 指导](https://docs.spring.io/spring-session/reference/configuration/redis.html)；登录显式保存 SecurityContext 依据[官方持久化说明](https://docs.spring.io/spring-security/reference/servlet/authentication/persistence.html)。

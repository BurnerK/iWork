# #11 身份与个人会话实施合同

2026-11-16 用户批准，供实现和独立审阅使用。范围仅账号身份与个人会话；不加入注册、消息、模型调用、任务或项目空间。

- Java 21 / Boot 4、Security、JDBC、Flyway、MySQL；无 ORM。新迁移保留 V1。
- username/password 登录，无注册。外部秘密配置仅在初始 ADMIN 不存在时初始化，首次强制改密；重复启动不覆盖。管理员创建普通 USER、启停、重置密码，不提供角色编辑或禁用初始管理员。创建/重置生成的临时密码仅响应一次。
- 部署恢复命令复用后端密码逻辑，隐藏交互输入或受保护文件，禁止命令参数和日志承载密码。恢复、重置、改密撤销所有旧登录；自行改密校验当前密码后重新登录。
- BCrypt；密码至少 12 字符且不超过 72 UTF-8 字节；连续 5 次失败锁定 15 分钟。
- users 保存唯一 username、displayName、hash、role、enabled、mustChangePassword、authVersion、失败/锁定及时间字段；conversations 保存 id、ownerId、title、时间及 owner/order 索引。
- 密码和状态变更与 authVersion 递增为单一事务。每个受保护请求检查最新 enabled/authVersion；重新启用不会复活旧登录。业务写入在同一事务重新锁定并核验调用者，避免检查后撤权竞态。
- Spring Session Redis，HttpOnly / SameSite=Lax，生产 Secure，显式 localhost 开发可关闭 Secure。Cookie 可跨浏览器重启，最长 30 天；服务端闲置 7 天、登录起绝对 30 天。登录轮换 Session ID，绝对期限不随访问延长。
- 保留登录、退出及所有写入 CSRF。GET /api/auth/csrf；form POST /api/auth/login；GET /api/auth/me；POST /api/auth/logout；POST /api/auth/password。临时密码会话仅能 me/password/logout 和必要的 csrf。
- 管理 API：GET/POST /api/admin/users、PATCH /api/admin/users/{id}/status、POST /api/admin/users/{id}/reset-password。
- 会话 API：GET/POST /api/conversations、GET /api/conversations/{id}。默认标题“新会话”，分页、创建、打开空详情。所有查询按可信身份限定 owner，无管理员绕过；拒绝伪造 owner 字段。
- JSON 错误：身份缺失/失效 401，权限/CSRF 403，不存在或其他 owner 404，重复 username 409，MySQL/Redis 不可用 503；无内存降级，不计作密码失败。敏感响应禁止缓存。
- Redis 仅保存登录态；Compose 官方镜像固定版本和摘要，AOF 持久卷、内部网络、不发布端口、健康检查。readiness 包括 Redis、MySQL 和文件目录。正常重建保留登录及业务；Redis 丢失只要求重新登录；不兼容升级可使登录失效，需文档说明。
- Vue 3 / TypeScript / Vue Router hash 路由，无 Pinia/UI 库。登录、改密、个人会话、用户管理；直接链接和刷新可用。错误/加载/空态、键盘、hover/focus/disabled 遵守 DESIGN.md。
- 验证边界已批准：真实 HTTP + MySQL/Redis（不 mock 认证），可控时钟覆盖期限；Vue 测试覆盖页面交互；Compose 验证重建及故障；真实浏览器验证管理员首改、建用户、用户首改、新建/打开会话、重置/禁用。

Redis 是本次已批准的架构修订，取代旧文档“不引入 Redis”的限制。业务真相仍只在 MySQL/文件目录。

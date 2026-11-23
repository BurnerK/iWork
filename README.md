# iWork

自托管的 AI 工作应用。用户可以在个人会话中使用自己的资料生成 Markdown 文档，并按版本找回和续改。

## 技术栈

- Java 21；交付镜像 Temurin 21.0.13+9，镜像摘要固定在 `Dockerfile`。
- Maven 3.9.11 / Wrapper 3.3.4：使用 `backend/mvnw`，无需系统 Maven。
- Node 26.13.1 / npm 11.20.0：版本在 `.nvmrc` 和前端 `package.json`。
- Spring Boot 4.0.9 / AgentScope Java 2.0.4 / MySQL 8.4.7；Vue 3.5.45 / TypeScript 5.9.3 / Vite 8.3.8。前端传递依赖由 lockfile 固定，后端使用 Boot BOM。
- Docker Engine 与 Compose v2 或更高版本；Python 3 用于 Compose 验收脚本。

## 本地开发（推荐）

先安装上述 Java、Node/npm，并启动 Docker。macOS / Linux 可直接运行，Windows 使用 WSL；使用 nvm 时先执行 `nvm install && nvm use`。

在仓库根目录运行，无需先在根目录执行 `npm install`：

```sh
npm run setup
npm run dev
```

`setup` 安装前端锁定依赖，并首次生成权限为 `0600` 的 `.env.development`，包含随机数据库密码和管理员初始密码。重复执行保留已有配置；前端依赖变更后再次执行即可。根目录 npm 仅作为命令入口，没有额外依赖。

`dev` 检查应用端口，启动并等待独立 MySQL / Redis 就绪，再运行本机 Maven 后端和 Vite 前端；同一终端显示带来源标识的日志。后端 readiness 通过后输出就绪提示。首次 Maven 下载可能较慢，超时后可重试，构建错误保留在终端。

打开 **http://127.0.0.1:5173**，使用 `.env.development` 中的管理员账号和初始密码登录，首次改密后重新登录，并删除文件中的 `IWORK_BOOTSTRAP_PASSWORD` 配置。已有管理员不会被配置覆盖。前端支持热更新；修改 Java 后停止并重新运行，或使用 IDEA 调试。

按 Ctrl+C 停止前后端，运行 `npm run dev:down` 停止开发数据库并保留数据。

## 文档

- [产品定位与功能范围](docs/PROJECT-SPEC.md) · [项目架构与目录](docs/ARCHITECTURE.md)
- [页面结构](docs/PAGE-STRUCTURE.md) · [视觉规范](DESIGN.md) · [组件开发规范](docs/COMPONENT-GUIDELINES.md)
- [协作约定](AGENTS.md) · [开发与组件发布](docs/DEVELOPMENT.md) · [构建与部署](docs/DEPLOYMENT.md)

## 许可证

iWork 的原创代码采用 [Apache License 2.0](LICENSE)。随仓库分发的第三方图标适用其[随附许可证](frontend/public/assets/icons/LICENSE)。

# #15 原始资料与任务输入契约

首版接收 UTF-8 `.txt` 与 `.md`，单份 1–2097152 字节，文件名最长 200 字符，不接收路径、控制字符、空文件或其他后缀。上传阶段只保存字节，UTF-8 解码及内容处理由 #16 的受限执行环境完成；后缀不代表内容已经验证。首版不包含 Office/PDF、外链抓取或知识库。

`POST /api/materials` 使用 `application/octet-stream` 原始请求体及 URL 编码的 `X-File-Name`，沿用身份和 CSRF。成功返回 201，响应的 `data` 含 `id/name/format/byteSize/createdAt`；400 表示名称或完整性错误，413 超限，415 格式不支持，503 存储不可用。断线后先刷新资料列表核对，重复上传创建独立资源而不覆盖同名文件。

`GET /api/materials?page=0` 每页 20 条；`GET /api/materials/{id}` 与 `GET /api/materials/{id}/content` 只返回调用者所有资源，跨用户与不存在均为 404。下载强制 attachment/octet-stream，并验证长度和 SHA-256。公开接口不返回宿主机路径，不直接渲染文档 HTML。

文件保存在配置文件根目录下的 `originals/{uuid}`，先写临时文件并 force，再原子移动，最后提交数据库索引。该目录独立于会话与临时工作区。失败上传不产生可引用的半成品。文件成功而数据库提交结果不确定时保留完整孤立文件，避免误删已提交资源；运维清理须在服务停止并核对数据库索引后进行，不按文件年龄自动删除。

AG-UI 的 forwardedProps 可带 `materialIds`（默认空，最多 5 个不重复 UUID）。业务层在受理时检查所有权，引用写入同一事务并纳入幂等指纹；重试不能偷偷替换资料。`GET /api/tasks/{id}/materials` 返回任务所引用的获准资料。#16 使用 MaterialService 的授权元数据/原文读取能力，不接受客户端文件路径。

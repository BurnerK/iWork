# #12 模型配置、授权与个人额度

本票交付配置闭环；真实 Harness 调用与计量由 #14 实施。沿用已确认的业务 API、真实身份/MySQL 和关键页面测试边界。

## 配置合同

- 上游连接为 HTTPS base URL、上游模型标识与显示名称。URL 禁止 userinfo、query、fragment，防止误把凭据填入地址；本票保存配置，不向上游发请求或证明模型可用。
- 凭据由部署者通过 `IWORK_MODEL_KEY_<名称>` 环境变量注入主应用。管理页面仅填写引用名称，保存时检查引用存在且非空；数据库存引用，API 不返回密钥。不接受密钥正文，也不读取其他前缀的环境变量。
- 本机 Java 启动时导出该环境变量；Compose 部署须在私有 override 文件的 `app.environment` 中显式传入所需变量，单写 `.env` 不会自动传进容器。真实值不得提交到仓库，修改后重建主应用。#14 在每次执行时再次检查引用，不因保存成功默认密钥永远有效。
- 实例初始币种 CNY；首次创建模型或个人预算前可设置有效 ISO 币种，之后锁定。所有配置写入与币种锁定在短事务中协调。首版不提供兑换或重置币种。
- 输入/输出价格按每百万 Token 分别设置；金额接口使用十进制字符串，数据库 `DECIMAL(18,6)`，非负、最多 12 位整数/6 位小数，禁止指数写法。预算为累计上限，不周期重置；修改上限只更新 limit，保留 spent/reserved。可用金额最小为零。#14 负责逐调用价格快照和实际核算，不能使用修改后的价格重算旧调用。
- 模型可停用或重新配置，无删除入口；授权是用户与模型的显式关联，管理员也需授权才能在个人入口看到模型。停用模型不出现在个人列表，重新启用保留原授权；撤权立即影响下一次列表读取。

## API

沿用 Session、CSRF、首次改密限制与实时身份检查。所有 `/api/admin/**` 仅管理员，配置写入事务内再次检查身份。

| 方法与路径 | 内容 |
| --- | --- |
| GET/PUT `/api/admin/model-settings` | `{currency}`，读取另返回 `locked` |
| GET/POST `/api/admin/models` | 模型列表/创建；字段 name、baseUrl、modelName、credentialRef、inputPrice、outputPrice、enabled |
| PUT `/api/admin/models/{id}` | 全量替换上述配置，保留模型身份与授权 |
| GET/PUT `/api/admin/users/{id}/model-access` | 读取模型 ID 数组；写入 `{modelIds:[]}` 全量替换授权，最多 100 个，不允许重复/未知 ID |
| GET/PUT `/api/admin/users/{id}/budget` | 读取预算；写入 `{limit:"10.000000"}` |
| GET `/api/models` | 本人获授权且启用的模型，仅 id/name/两项价格 |
| GET `/api/budget` | 本人 currency/limit/spent/reserved/available；尚未设置默认零 |

非法配置 400，非法身份/权限 401/403，未知目标 404，已锁定币种变更 409，存储不可用 503。响应不包含密钥、SQL 或异常细节。

V3 追加 model_settings、models、model_access、budgets，不修改 V1/V2。原有账号与会话保持。默认拒绝用户指定 owner 查询他人额度；普通用户没有读取模型连接或凭据引用的 API。

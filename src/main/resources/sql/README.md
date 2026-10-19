# 账号与会话数据库

`create_ai_agent.sql` 面向 MySQL 8.0+，创建 `ai_agent`、`user` 和 `chat_session`。
脚本可重复执行，只创建缺失的库、表，不清空数据或建立示例账号。本机已执行该脚本。

业务库与 PostgreSQL 知识库分开：

- `spring.datasource`：MySQL，用户与会话索引，MyBatis Plus 的主数据源。
- `app.vector-datasource`：保留现有 PostgreSQL 连接，专用于 pgvector。
- `app.vector-store.enabled: true`：启用现有知识库。
- `app.vector-store.initialize-data: false`：默认跳过启动时的文档加载、关键词生成和向量写入，仍可查询已有知识库。未配置此项时也默认关闭。

需要首次导入或手动重新导入 `classpath:document/*.md` 时，将 `app.vector-store.initialize-data` 改为 `true` 并重启。
开启时会导入当前全部文档，不会自动判断哪些文档是新增内容；导入完成后改回 `false`，避免下次启动重复写入。
该开关控制文档数据导入，向量表和索引的结构初始化仍由现有 PgVector 配置负责。

连接口令、模型凭据和 SMTP 设置保存在被 Git 忽略的 `application-local.yml`，可通过环境变量覆盖。
公共 YAML 配置 Redis 数据库 2、独立的 `huiling:session` 命名空间和 `spring.session.timeout: 30d`。
Spring Session Redis 自动接管 `HttpSession`，应用代码只保存用户 ID、认证版本和 CSRF 标识。
Cookie 为 HttpOnly、SameSite=Lax；HTTPS 部署设置 `SESSION_COOKIE_SECURE=true`。

## Spring MVC 分层

```text
domain/entity     用户和会话实体
domain/dto        请求参数对象
domain/vo         返回对象，不含密码哈希或内部文件路径
controller        HTTP 接口与请求校验
service           业务接口，Controller 和业务组件依赖这些契约
service/impl      接口实现，认证、密码、会话归属、AI 执行和文件记录
mapper            MyBatis Plus 数据库访问
manager           邮件发送和 Redis 验证码
config            数据源、Session、Web 请求保护等配置
exception         统一错误响应
```

## 登录与账号

- `GET /api/auth/session`：获取当前用户及 CSRF 标识，未登录返回 `user: null`。
- `POST /api/auth/email-code`：`{email, purpose}`，purpose 为 `register`、`reset_password` 或 `login`。
- `POST /api/auth/register`：`{email, code, password, confirmPassword, displayName}`，昵称必填、1 至 40 个字符，注册后登录。
- `POST /api/auth/login`：`{email, password}`。
- `POST /api/auth/code-login`：`{email, code}`，已注册账号使用登录验证码登录，无需密码。
- `POST /api/auth/nickname`：`{displayName}`，需要登录，只更新当前用户的昵称，保留会话与密码。
- `POST /api/auth/password`：`{currentPassword, password, confirmPassword}`，需要登录。
- `POST /api/auth/reset-password`：`{email, code, password, confirmPassword}`。
- `POST /api/auth/logout`：退出登录。

密码使用 BCrypt；验证码有效期 5 分钟、发送间隔 60 秒、限制邮件及 IP 频率，最多 5 次错误验证。
验证码按邮箱和用途分别存储，注册、登录与找回密码的验证码不能混用，经 Redis 原子操作成功消费后失效。
密码变更增加 `auth_version`，旧 Session 不能继续访问保护接口。昵称使用现有 `user.display_name` 字段，不需要数据库迁移。
前端左下角账号入口提供修改昵称、修改密码；登录窗口可切换密码或验证码登录，并提供忘记密码入口。
登录时轮换 Session ID 与 CSRF 标识。前端自动携带 Cookie 和 `X-CSRF-TOKEN`。
浏览器同时发送 `X-Account-ID`，防止其他标签页切换账号后把结果写入旧账号缓存。

## 会话与文件

- `GET /api/chats?appId=love|manus`：当前用户的最近 100 个会话。
- `GET /api/chats/{id}`：读取当前用户的页面消息文件。
- `PUT /api/chats/{id}/title`：`{title}`，修改当前用户的会话名，去除首尾空白后为 1 至 100 个字符；不改变会话 ID、历史或正在执行的任务。
- `DELETE /api/chats/{id}`：数据库逻辑删除；取消任务并清理该会话的 `.json`、`.kryo` 及对应压缩备份，其他用户和应用的文件不受影响。
- `POST /api/chats/{id}/cancel`：只停止当前用户的指定会话任务，适用于恋爱大师与智能体。
- 现有 `/api/ai/**` 接口均要求登录，聊天、回答及取消会校验所属用户。

数据库记录聊天室 ID、用户 ID、应用类型、标题和相对文件路径。前端不能提交任意文件路径。
默认存储布局：

```text
tmp/chat-memory/
  love/<userId>_<chatId>.kryo   恋爱模型的历史摘要及近期原文
  love/<userId>_<chatId>.json   完整的页面消息
  manus/<userId>_<chatId>.kryo  智能体模型和工具上下文
  manus/<userId>_<chatId>.json  每一步、追问、真实补充回答与完成状态
  love/context-backups/       每次成功压缩前的模型记忆快照
  manus/context-backups/      每次成功压缩前的完整工具记忆快照
```

服务器负责写入消息文件，不接受前端覆盖历史。文件通过临时文件和原子替换保存。
消息文件记录 `kind: progress|final`，用于区分可折叠的执行进展和始终完整显示的最终答案；字段保存在 JSON 会话文件中，无需改变数据库表。
列表与详情返回 `running`，表示服务器当前是否仍在执行该会话。任务与 SSE 连接分别管理：切换页面、切换对话或浏览器断开连接都不会自动取消任务。
前端按会话保存请求与追问状态，侧栏显示执行标识；重载页面后读取服务器历史，并定时更新仍在执行的会话，不重新发送原任务。
点击停止或删除会话才会显式取消对应任务。服务器进程关闭后，任务不会自动恢复执行；已有消息仍保存在文件中。
旧版没有账号归属的记忆及浏览器记录不自动绑定到任何账号。
已删除会话不会再次从列表或文件接口返回，也不能用原 ID 重新创建。
删除先关闭记录器，并给模型上下文设置进程内删除标记，防止迟到的回复、收尾和压缩回调重新生成文件。文件清理失败时会话仍保持逻辑删除，重复 DELETE 会继续清理；不会通过回滚恢复已删除会话。只删除该会话的记忆与记录，不清理工具产生的下载文件或报告。

前端历史按应用显示图标、标签与筛选；本机缓存按用户隔离。
生产构建的 Service Worker 只缓存页面和静态资源，不缓存认证或聊天接口。
首次联网加载生产构建后，可真正断网重载并阅读已缓存的会话；离线不能发送消息。
离线删除先隐藏并保存待删除标记，恢复连接后同步逻辑删除与文件清理。修改对话名需要联网。未加载到本机的历史需要联网后读取。

按用户确认，所有注册账号视为可信用户，保留现有智能体工具权限。

## 上下文压缩

两个应用启用“历史摘要 + 近期原文”，取代恋爱大师直接淘汰第 20 条之外消息的处理方式。
摘要由 Spring AI Alibaba 1.1.2.0 的 `SummarizationHook` 使用该应用当前模型及框架默认摘要提示词生成，不调用工具。
项目通过 `HookContextSummaryGenerator` 适配调用预算、输出校验和异常传播；很长的历史使用 Spring AI `TokenTextSplitter` 分块，由 Hook 逐段合并，任何一段失败都不覆盖原记忆。
`ContextMemoryService` 定义业务契约，`ContextMemoryServiceImpl` 保留业务侧的阈值、最近 4 轮及完整工具链选择、手动请求排队、文件存储与压缩状态。选出的旧前缀传给 Hook 时强制触发摘要，避免手动请求被 Hook 的阈值再次拦截。
工具参数和结果先转为可读历史数据，避免框架默认读取 `ToolResponseMessage.getText()` 时遗漏内容；摘要最终以普通 AI 历史消息保存，不作为系统指令。
页面完整聊天记录仍由 `.json` 文件保存，压缩不改变数据库或页面历史，无需新增 SQL 表或字段。

```yaml
chat-memory:
  compaction:
    enabled: true
    auto-enabled: true
    threshold: 0.70
    target-ratio: 0.40
    retain-turns: 4
    summary-max-tokens: 2048
    apps:
      love:
        context-window-tokens: 32768
        response-reserve-tokens: 4096
      manus:
        context-window-tokens: 32768
        response-reserve-tokens: 4096
```

这里的 32K 是应用工作预算，并非模型供应商或代理网关的窗口上限声明。输入预算为窗口减去回复预留量，即默认 28672 tokens。
自动压缩阈值为输入预算的 70%；近期内容较大时尽量保留 4 轮，在预算允许的范围内减少保留轮数。
智能体单个任务较长时，可继续摘要化已经闭合的工具步骤，保留最新用户目标和未完成的工具调用。
`target-ratio` 用于长任务的压缩目标，不保证每次恰好降到 40%；摘要长度受 `summary-max-tokens` 限制。

- `GET /api/chats/{id}/context`：返回占用估算、预算、自动阈值、压缩状态及上次压缩前后计数，不返回摘要或内部路径。
- `POST /api/chats/{id}/context/compact`：请求手动压缩。正在回复或等待工具/用户回答时，先排队，到下一次模型调用前或本轮完成后执行。

接口通过 Controller、Service 及现有 Mapper 校验用户归属、逻辑删除与 CSRF。状态按用户和应用的记忆 ID 隔离。
状态包括 `idle`、`queued`、`compressing`、`completed`、`noop`、`error`；摘要与上次压缩计数写入模型记忆元数据，可重启恢复。
占用通过本地 tokenizer 估算，包含消息、工具参数与结果、系统提示和工具定义；不同供应商的编码和协议包装可能不同，前端标注“估算”。
压缩失败后自动尝试冷却 60 秒，手动重试仍可用。若不能安全压缩且输入超过配置预算，会明确报错，不发送超预算请求。
智能体的控制提示每次模型调用只添加一次，不再逐步累积。显式停止任务也会打断执行线程，避免压缩结束后再调用任务模型。

## 验证

普通单元与数据库权限测试使用 H2 的 MySQL 模式、模拟邮件和模型。
`RUN_REDIS_INTEGRATION_TESTS=true` 启用本机 Redis 验证，使用随机测试邮箱和独立 Session 命名空间，结束后只清理测试自身键。
浏览器的账号、离线恢复测试使用独立模拟后端，不建立真实用户或发送邮件。
SMTP 已验证连接与授权，没有发送测试邮件。部署或实际使用需重启后端；模型与现有知识库继续使用本地配置。

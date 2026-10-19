# 绘灵 · AI 工作空间

Vue 3 + Vite + Vue Router + Axios 前端，包含应用主页、AI 恋爱大师和 AI 超级智能体聊天室。

## 启动

本项目使用兼容当前 Node 20.18 环境的 Vite 6。需要 Node.js 20 或更高版本。

```powershell
# 从项目根目录进入前端目录
cd ./ai-agent-frontend
npm install
npm run dev
```

访问 http://127.0.0.1:5173 ，后端应在 http://localhost:8124 启动。开发服务器已将 `/api` 请求代理到后端，因此本地开发不需要修改后端跨域配置。

```powershell
npm test       # SSE 解析及接口行为测试
npm run build # 生成 dist
npm run preview # 在 http://127.0.0.1:4173 预览构建结果，也包含本地后端代理
```

## 功能

- 主页展示两个应用。桌面侧栏可收起为图标栏，保留应用、新对话、最近对话和账号入口，并记住展开状态；手机端使用抽屉导航，支持点击遮罩或 Escape 关闭及键盘焦点约束。
- 使用浅灰背景、白色聊天区和应用强调色，统一正文对比度、卡片间距与输入框宽度。新对话从欢迎内容顶部开始；已有记录继续定位到最新消息。侧栏切换不改变草稿或中断正在执行的任务。
- 进入聊天页自动创建唯一会话 ID。恋爱大师的每次请求都带同一个 `chatId`；新建对话会生成新的 ID。
- 用户消息在右、AI 消息在左。流式回复、Markdown、代码块和表格均可显示。
- 用户消息和最终回答使用较大的正文字号（桌面 17px、手机 16px），思考和工具响应使用较小字号（14px、13px）。每个代码块有独立复制按钮，只复制代码本身，保留缩进与换行；支持复制成功/失败反馈。折叠的执行进展展开后也可复制其中的代码。
- 恋爱大师逐段追加文字；超级智能体采用类似 ChatGPT 的开放式文字回复，每条 SSE 响应都是独立消息块，按时间排列，不显示“步骤 N”编号。用户消息在右侧，提问与后续执行各自显示。
- Enter 发送，Shift + Enter 换行；兼容中文输入法，空白输入和重复发送被禁用。
- 点击建议卡片填入示例问题，用户主动发送后才调用模型。
- 支持停止接收、复制回复、复制会话 ID、失败提示和主动重试。
- 用户输入、执行进展和追问超过两行默认折叠，点击“展开 / 收起”控制阅读；最终答案始终完整显示，没有折叠按钮。搜索工具展示完整的标题、链接及摘要，网页工具展示可读正文；同一消息内完全相同的工具结果只展示一次。脚本、原始 HTML、无关 JSON 和编码异常文字不直接展示，复制回复包含可读内容。
- 智能体在执行期间持续显示“正在思考…”动画，收到一条进展不会让提示消失；等待用户补充时显示“等待你的补充…”。完成、停止及报错后提示消失。
- 服务器返回错误时，输入框上方显示“回答出错~”，约 2.6 秒后自动消失；历史中保留简洁的重试入口。
- 回复时自动跟随到底部；用户向上阅读时暂停自动滚动。
- 登录窗口支持邮箱加密码、邮箱加验证码两种登录方式，未注册邮箱可通过验证码注册并设置昵称。账号通过 Redis Session 保持 30 天登录状态。
- 左下角账号入口可修改昵称、修改密码；忘记密码可在登录窗口通过邮箱验证码重置。昵称保存后即时更新，在页面刷新、其他标签页和离线缓存中保留。
- 用户、会话索引保存在 MySQL `ai_agent`，完整页面记录由后端写入会话文件。侧栏通过图标、应用标签及筛选区分恋爱大师与超级智能体。
- 侧栏提供修改对话名，名称为 1 至 100 个字符，联网保存到数据库并更新本机缓存，执行中的回复继续正常运行。
- 删除会话时数据库保留逻辑删除的行；对应聊天记录、模型记忆和压缩备份会清理。离线删除会在恢复连接后同步，文件清理失败会继续重试。
- 当前设备按用户分别缓存最近 100 个会话。生产构建首次联网加载后可以断网重载并阅读已缓存的历史；离线时不能发送消息。Service Worker 只缓存页面和静态资源，认证及聊天接口不入缓存。

## 接口与 SSE

| 功能 | 路径 | 请求 |
| --- | --- | --- |
| 恋爱大师 | `/api/ai/love_app/chat/sse` | POST JSON：`message`, `chatId` |
| 超级智能体 | `/api/ai/manus/chat` | POST JSON：`message`, `chatId`, 可选 `runId` |
| 连接检查 | `/api/health` | GET，无参数 |

两个流式聊天接口使用 POST 正文发送长文本，避免中文查询参数超过请求行长度限制；输入框最多 8000 字符。

使用 Axios 的 `fetch` adapter 和 `responseType: 'stream'` 消费浏览器 ReadableStream。没有使用 EventSource 自动重连：后端关闭一次 SSE 响应后，前端即结束这一轮，避免重复触发智能体任务。解析器支持 LF/CRLF/CR、跨分片的中文、多行 data、空格、注释心跳以及可选 `[DONE]` / `done` / `complete` 结束标记。只有用户主动点击重试才重新提交。

Manus 执行进展继续使用普通 SSE data；最终答复单独使用 `event: final_answer`，data 为完整的回答文本。
会话页面文件与本机缓存的消息保存 `kind: progress|final`，最终答案在刷新或离线恢复后仍完整显示。
恋爱大师的流式回答视为最终答复，也完整显示。达到执行步数上限的提示属于执行进展。

### AskHuman 真实交互

智能体缺少必要信息时，后端通过 `human_question` SSE 事件发送 `{runId, questionId, question}` 并暂停工具调用。前端显示问题，输入框切换为“提交补充”；实际回答在右侧显示，后续进展在左侧显示。

- `POST /api/ai/manus/answer`，JSON：`{runId, questionId, answer}`。
- `POST /api/chats/{chatId}/cancel`，取消指定会话正在执行的任务，两个应用共用此入口。
- 回答只对应当前任务中的这一条问题；重复、过期及跨任务回答会被拒绝。
- 回答继续同一个 Agent 的执行，不重新发起 `/manus/chat`；页面重载后也能从服务器历史恢复有效追问并提交补充。
- 发送失败时保留输入内容，可以重试；暂停等待时可以结束任务。
- 默认等待回答 10 分钟，后端配置 `manus.human-answer-timeout-ms` 可以调整。任务结束、等待超时或显式取消后，等待对象和任务注册表都会释放。浏览器断线只关闭传输通道，任务及其等待状态继续保留。
- 任务和等待回答在后端内存中保存。重启后端或刷新页面后，旧的提问不能继续提交，需要重新发起任务。

## 后端能力边界

- Manus 每次发送创建新的执行实例，但会按 `chatId` 从自己的文件目录读取历史上下文，并在用户输入和每个完整执行步骤后保存。
- Manus 按执行过程返回工具结果与回答，并非逐字生成最终答案。调用 `doTerminate` 后会基于完整工具上下文与模型已有的文字进行一次禁用工具的模型调用，整理并保存最终答复后再结束连接。生成失败返回错误，不会假装成功结束。控制工具的“任务结束”文本和结束进展说明不作为最终回答。
- 网页抓取提取主内容并过滤脚本、样式，同时展示图片、视频、音频链接；支持懒加载、srcset、分享元数据和相对地址。嵌入页面单独标注，避免当作媒体直链；验证页面和未暴露的动态资源会明确说明。每类最多展示 30 条去重链接。
- MCP 图片搜索展示图片下载地址及 Pixabay 来源页面，空结果和限流等待时间直接可读；结构化原始数据继续隐藏。搜索结果、正文及媒体链接可以展开阅读，复制内容也保留这些链接。
- “停止接收”同时请求后端取消任务，释放待回答的问题并停止后续步骤。已经执行中的外部模型或工具不一定立即停止。
- 切换会话、新建会话、返回主页和页面重载不会取消任务。前端按会话保存请求与追问状态，侧栏执行标识表示仍在运行；返回后展示原会话最新结果，正在运行的其他会话不影响当前输入框。
- 重载后通过会话列表及详情的 `running` 状态恢复执行中的会话，每 1.5 秒更新服务器记录，不重复发起模型任务。`POST /api/chats/{id}/cancel` 是两个应用共用的会话取消接口，校验当前用户归属。
- 任务运行在当前服务器进程内；服务器关闭后不会自动恢复执行，已保存的历史仍可读取。
- AskHuman 仅在带真实回答通道的 Manus 流式任务中注册；直接调用三参数构造的 `MyManus.run()` 不会附加示例回答工具。
- 终端工具优先按 UTF-8 解码，兼容旧程序的 GBK 输出。
- 后端的 Spring Boot `RestClient` 使用 HTTP/1.1，避开部分模型兼容网关的 HTTP/2 `RST_STREAM` 问题。连接超时默认 30 秒，读取超时默认 180 秒，可分别通过 `ai.http.connect-timeout-seconds` 和 `ai.http.read-timeout-seconds` 配置。上游服务故障仍会通过 SSE 错误事件通知前端。
- PDF 和图片工具返回的是本地文件路径。后端尚无文件下载接口，前端显示路径，不生成无效下载按钮。
- SSE 在正常 EOF 或 `complete` 事件时结束，后端错误通过 `error` 事件返回；提问与任务元数据不会被误显示成普通执行步骤。

## 环境配置与部署

将 `.env.example` 复制为 `.env.local` 可以改变后端目标。

```dotenv
VITE_API_BASE_URL=/api
BACKEND_PROXY_TARGET=http://localhost:8124
```

正式部署时上传 `dist` 到静态服务器，同时反向代理 `/api` 到 Spring Boot 服务。聊天采用 hash 路由，不要求静态服务器额外配置路由回退。Vite 的开发代理不会被打包进 `dist`。

Nginx `/api` 代理示例（保留原始 `/api` 路径）：

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8124;
    proxy_http_version 1.1;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 1800s;
}
```

如果将 `VITE_API_BASE_URL` 设置为跨域的完整地址，后端需要允许该前端来源。环境变量在构建时注入，修改后需重新启动开发服务器或重新构建。

## 目录

```text
src/
  components/MessageBubble.vue  消息、Markdown 与执行步骤
  composables/useChat.js        会话状态、持久化与请求生命周期
  data/apps.js                 应用文案与示例问题
  services/api.js              Axios 请求、流式读取与错误处理
  services/sse.js              独立 SSE 协议解析
  views/HomeView.vue           主页
  views/ChatView.vue           共用聊天页面
  App.vue                     布局、应用导航与连接状态
  router.js                   Hash 路由
  styles.css                  视觉样式及响应式布局
```

构建后端真实模型调用需要你现有的模型和 MCP 配置。前端测试通过模拟 SSE 验证接口协议，不会调用付费模型。

## 两个应用的文件记忆

后端存储与浏览器显示记录分开管理。后端默认目录为：

```text
tmp/chat-memory/
  love/<userId>_<chatId>.kryo   恋爱大师，历史摘要及近期模型消息
  love/<userId>_<chatId>.json   完整页面消息
  manus/<userId>_<chatId>.kryo  超级智能体模型及工具上下文
  manus/<userId>_<chatId>.json  独立步骤、追问和补充回答
```

前端会持续发送当前聊天室的同一个 `chatId`，新建对话生成新 ID。超级智能体可以在下一轮或重启后端后继续读取已经保存的上下文；提问的真实回答也包含在工具结果中保存。内部“下一步提示词”不写入长期记忆。

两个应用即使使用相同 `chatId`，也只在各自目录读取和写入。旧版本直接保存在 `tmp/chat-memory/*.kryo` 的记忆不再读取或迁移，可手动清理。

账号和会话接口要求登录并校验用户归属。旧版无账号归属的浏览器记录不自动绑定到任何账号。
SQL、分层说明、登录接口和配置见后端 `src/main/resources/sql/README.md`；建库脚本为同目录的 `create_ai_agent.sql`。
本机已建立数据库和两张表。数据库、Redis、SMTP 口令位于被 Git 忽略的 `application-local.yml`。
MySQL 是业务主数据源，现有 PostgreSQL 知识库使用独立数据源。

生产构建的账号与离线验证：

```powershell
npm run build
$env:BACKEND_PROXY_TARGET = 'http://127.0.0.1:18126'
$env:FRONTEND_TEST_URL = 'http://127.0.0.1:5176'
python C:/Users/LiShuwen/.codex/skills/webapp-testing/scripts/with_server.py --server 'npm run preview -- --port 5176' --port 5176 -- python tests/account_browser_check.py
```

该测试使用模拟账号、邮件和模型接口，验证注册、验证码登录、昵称修改与跨标签页同步、密码变更及重置、用户隔离、应用分组、逻辑删除同步及真正断网重载。

会话改名与运行中改名验证：

```powershell
$env:BACKEND_PROXY_TARGET = 'http://127.0.0.1:18124'
$env:FRONTEND_TEST_URL = 'http://127.0.0.1:5175'
python C:/Users/LiShuwen/.codex/skills/webapp-testing/scripts/with_server.py --server 'npm run dev -- --port 5175 --strictPort' --port 5175 -- python tests/conversation_browser_check.py
```

该测试覆盖名称校验、保存与刷新、错误与离线提示、执行中改名后继续接收最终答案、键盘焦点及移动端操作。实际记忆和压缩备份的删除由后端临时目录测试验证。

后端配置 `chat-memory.base-dir` 可以更改根目录。两个应用始终在该根目录下使用独立的 `love` 和 `manus` 子目录。同一超级智能体会话同时只能运行一个任务，防止并发覆盖历史。新任务未传 `chatId` 时后端会生成独立会话 ID；正常前端调用始终传入聊天室 ID。

## 可选：浏览器测试

`tests/browser_check.py` 会启动独立的模拟后端（18124 端口），验证实时显示、取消、会话恢复、复制、错误重试及多个屏幕宽度，并将截图写入 `test-results`。

先安装测试工具：

```powershell
python -m pip install --target .test-tools -r tests/requirements.txt
```

在一个终端启动指向模拟后端的前端：

```powershell
$env:BACKEND_PROXY_TARGET = 'http://127.0.0.1:18124'
npm run dev
```

在另一个终端执行 `python tests/browser_check.py`。Windows 默认使用已安装的 Edge 无界面浏览器；其他平台可设置 `PLAYWRIGHT_CHANNEL=chrome` 并安装 Chrome。测试后关闭开发服务器，在新终端正常执行 `npm run dev`，恢复实际的 8124 后端目标。

## 上下文占用与压缩

输入框下方的圆环显示当前会话的上下文占用估算。点击圆环可以查看输入预算、回复预留量、70% 自动阈值和上次压缩前后的计数，并点击“压缩上下文”。
手动请求在任务运行中会排队，圆环显示等待或压缩中；返回原会话和刷新页面后仍可读取最新状态。
自动压缩尽量保留最近 4 轮原文，更早内容由 Spring AI Alibaba 的 `SummarizationHook` 使用框架默认提示词生成摘要；完整聊天记录始终保留。恋爱大师和智能体使用各自当前模型生成摘要。
离线时展示本机缓存并禁用手动请求；摘要失败或占用接口暂时不可用不会删除记录或影响阅读。
配置位于后端 `application.yml` 的 `chat-memory.compaction`，每个应用默认 32K 工作预算、4K 回复预留，可按实际模型和网关限制调整。

`tests/context_browser_check.py` 验证圆环、手动排队、自动压缩、刷新恢复、失败反馈和移动端布局：

```powershell
$env:BACKEND_PROXY_TARGET = 'http://127.0.0.1:18129'
$env:FRONTEND_TEST_URL = 'http://127.0.0.1:5179'
python C:/Users/LiShuwen/.codex/skills/webapp-testing/scripts/with_server.py --server 'npm run dev -- --port 5179 --strictPort' --port 5179 -- python tests/context_browser_check.py
```

`tests/background_browser_check.py` 使用独立后台任务模拟器，验证两个应用同时执行、切换会话、主页重载、有效追问恢复和按会话停止：

```powershell
$env:BACKEND_PROXY_TARGET = 'http://127.0.0.1:18128'
$env:FRONTEND_TEST_URL = 'http://127.0.0.1:5178'
python C:/Users/LiShuwen/.codex/skills/webapp-testing/scripts/with_server.py --server 'npm run dev -- --port 5178 --strictPort' --port 5178 -- python tests/background_browser_check.py
```

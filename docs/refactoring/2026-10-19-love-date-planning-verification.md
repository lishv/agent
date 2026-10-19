# 恋爱大师：地图检索、约会 PDF 与启动验证

## 原因与修复

`mcp-servers.json` 已经包含高德，但原应用配置注释了该文件的加载入口；运行时只连接图片 MCP。现在由 `application.yml` 单独装配高德官方 Streamable HTTP 连接，图片继续使用原有 SSE 服务，避免启动第二份图片服务。部署时设置 `AMAP_MAPS_API_KEY`；本地复用现有 Key，保存于被 Git 忽略的 `application-local.yml`。

约会任务的提示词独立到 `LoveAppPrompts`。模型应先搜索城市地点、核实详情，再按实际会合点查周边和必要路线，最后生成行程与 PDF。只有城市时不虚构用户位置；喜好未知时明确暂定假设；预算按双人总额拆分，并核对转场时段及金额合计。

流式入口继续检索知识库，但使用专用增强模板。情感资料只作参考，实时地点采用工具证据，避免默认“只能依据知识库回答”的指令与策划任务冲突。

地点图片需能核实来源。普通图库情侣照片不能冒充地点实景；缺少相关实景时采用纯文字 PDF。下载链接仍由既有文件服务生成。

## 自动化与构建

- 最终重建：94 项检查，93 项通过、1 项因 Windows 创建符号链接权限不足跳过，无失败。
- 覆盖地图 HTTP 配置及真实 MCP 协议、RAG 增强、DashScope 工具执行、用户文件上下文、文件下载、记忆与摘要压缩、后台执行和 Manus 交互。
- 地图配置检查从实际 `application.yml` 装配 HTTP 传输，连接本机 MCP 测试服务，验证 Key 参数、工具发现和城市搜索回调。
- 构建产物为 `target/ai-agent-0.0.1-SNAPSHOT.jar`，已检查包含地图配置、新提示词及专用 RAG 模板。
- 日志：`.tmp/startup-rebuild.log`、`.tmp/class-verification.log`。

## 联机验证范围

读取当前本地配置中的 `qwen3.8-flash`，使用真实 `LoveApp`、ChatClient、DashScope 适配器、高德与图片 MCP，以及实际 PDF 工具。为隔离既有本地嵌入模型的资源问题，实测夹具仅替换向量检索与记忆存储；未将其替身作为线上嵌入服务正常的证据。

首次完整实测使用用户给出的“合肥、520 元、喜好未知、暧昧期、生成 PDF”请求，调用 15 次工具：城市搜索、地点详情、周边搜索、步行与公交路线、地图链接、PDF 生成。三页 PDF 包含具体地点、地址、交通、分项预算和待确认事项；预算分项合计 520 元，未插入通用情侣照片，三页已渲染检查。

检查该 PDF 时发现转场时段短于路线完整耗时，因此补充了生成前的时间与预算核对要求。第二轮实测已调用地图工具，但云端响应流发生 `Connection reset`，未生成 PDF，不能计为完整通过。记录分别位于 `.tmp/love-date-live/` 和 `.tmp/love-date-live-final/`。

最终有界重试完成 28 次工具调用，生成九页 PDF 并返回原始下载链接。下载的五张图片资源全部匹配本轮高德地点详情返回值，分别对应合柴1972、趣陶坊、罍街、查令84号咖啡馆和天鹅湖公园；其中一条链接仅将 HTTP 升级为 HTTPS，主机、资源路径与参数一致，未使用通用情侣图库图片。预算分项为 176、168、78、25、25、30、18 元，合计 520 元。所选约 18.5 分钟的驾车转场在行程中留出 75 分钟，包含等车和其他缓冲。九页已渲染检查，照片、中文和表格可读。

最后一轮记录位于 `.tmp/love-date-live-verified/`，过程日志为 `.tmp/love-date-live/verified-run.log`。该夹具采用独立的测试文件根目录，不能把其中的 `/api/files/...` 链接当作当前运行服务器已有的附件链接；应用内实际下载由自动化测试覆盖。

工具顺序及内容质量由提示词约束，并非代码中的确定性工作流。模拟测试证明接线、上下文和提示词传递；联机样例用于核对实际模型的选择，不能据此保证每次生成都相同。

## StorageProperties 启动错误

用户日志的根因是 `ClassFormatError: Invalid method Code length 0`。现场复现了 `StorageProperties.class` 无法加载；松散类文件与之前成功构建的 JAR 中对应文件不同。

同时运行的 VS Code Red Hat Java/JDT 自动构建会写入这个项目的 `target/classes`，与 IDEA/Maven 共用输出目录。该工作区新增 `.vscode/settings.json`，设置 `java.autobuild.enabled=false`，并清理重建编译产物；IDEA/Maven 继续负责构建。此设置属于当前工作区的本地编辑器配置。

修复后检查全部 115 个应用类：均能被 JVM 加载，不含错误编译占位代码，与 JAR 内对应字节码全部一致。随后以隔离端口和测试存储目录启动完整 JAR，约 8 秒启动成功，`GET /api/health` 返回 `200`、正文 `ok`。验证进程已停止，用户可重新运行 IDEA 项目。

相关说明：[高德官方接入](https://lbs.amap.com/api/mcp-server/gettingstarted)、[VS Code Java 自动构建配置](https://github.com/redhat-developer/vscode-java/blob/main/README.md)。

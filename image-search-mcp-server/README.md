# 图片搜索 MCP 服务

`searchImage(query)` 只检索 Pixabay 图片库，推荐使用 `mountain lake`、`campus landscape` 或 `校园 风景` 等简短主题关键词。
服务兼容移除误传的 `site:pixabay.com` 和 `filetype:`；其他站点限定、网址、空关键词和超过 100 字符的输入会返回明确错误，不发送请求。

返回 JSON 字符串，保持 MCP 工具名称和 `query` 参数不变：

- `ok`：`images` 包含图片 `url`、较大尺寸的 `downloadUrl`、Pixabay 来源 `pageUrl`、标签、作者和尺寸。下载工具可使用 `downloadUrl`。
- `no_results`：没有匹配图片，建议简化主题关键词；不会再返回空字符串。
- `invalid_query` / `not_configured`：输入不合适或缺少 API Key。
- `rate_limited`：HTTP 429，`retryAfterSeconds` 给出等待时间。等待期间不继续请求上游，已缓存的结果仍可读取。
- `upstream_error` / `network_error`：上游 HTTP、返回格式或连接错误。结果不会包含 API Key、完整请求 URL 或原始错误正文。

单次最多返回 10 张图片，连接超时 3 秒、读取超时 10 秒；网络请求不占用全局方法锁，慢查询不会阻塞其他用户。正常结果及空结果缓存 24 小时，保留约 128 个查询；限流优先读取 `Retry-After`、`X-RateLimit-Reset`，缺少有效值时等待 60 秒。
中文关键词使用 `lang=zh`，其他关键词使用 `lang=en`。

保留现有 `pixabay.api-key` 配置，让客户端重新读取工具描述。

`mvn test` 使用模拟 HTTP 响应检查结果、关键词、缓存和限流，不调用真实 Pixabay 接口。Spring 装配测试禁用 stdio 传输，避免占用测试进程的标准输入。

主应用会为该 MCP 的 `searchImage` 包装数量限制与真实尺寸标注，普通图片优先使用本服务；超时或结果不足时使用 Bing 图片检索补充。缩略图和预览图同样保留，`isPreview` 标记是否低于目标尺寸，不能把预览图声称为 4K 原图。Pixabay 的 `largeImageURL` 最大边通常为 1280px。更新此子项目后需重新打包并替换远程图片服务 JAR，再重启服务；仅重启主应用不会更新远程 JAR。

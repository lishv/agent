# 业务异常规范

业务校验使用 `ThrowUtils`，业务执行失败使用 `BusinessException`。所有错误码由 `ErrorCode` 管理，不在 Controller、Service 或 Manager 中分别定义。

```java
ThrowUtils.throwIf(message == null || message.isBlank(), ErrorCode.PARAMS_ERROR, "消息不能为空");

try {
    // 执行业务操作
} catch (IOException cause) {
    throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "对话文件暂时不可用，请稍后重试", cause);
}
```

`GlobalExceptionHandler` 统一处理业务异常、JSON/参数校验、Spring MVC 框架异常及未知异常。REST 失败返回 `{ "code": 40000, "message": "消息不能为空" }`，并保留对应 HTTP 状态；成功响应仍使用现有结构。示例项目使用的统一成功响应包装未引入，避免改变现有前端及 SSE 协议。

| 场景 | 错误码 | HTTP 状态 |
| --- | --- | --- |
| 参数错误 | 40000 | 400 |
| 未登录 / 登录验证失败 | 40100 | 401 |
| 禁止访问 / CSRF 校验失败 | 40300 | 403 |
| 会话不存在或无权读取 | 40400 | 404 |
| 任务冲突 | 40900 | 409 |
| 当前浏览器账号已切换 | 40901 | 409 |
| 任务已结束 | 41000 | 410 |
| 限流 | 42900 | 429 |
| 未知系统异常 / 业务执行失败 | 50000 / 50001 | 500 |
| 文件、邮件服务不可用 | 50300 | 503 |
| 等待用户回答超时 | 50400 | 504 |

流式响应开始前的失败仍返回 JSON 和对应 HTTP 状态。响应开始后，恋爱大师和智能体通过 SSE `error` 事件发送安全提示，已产生的聊天内容保留。`ErrorResponse` 统一选择对用户展示的提示；未知异常的底层详情仅记录在服务器日志中。

配置初始化校验、Java I/O 与任务取消属于框架或控制流语义，保留必要的原生异常。不要为了类型统一而把 `CancellationException` 转成业务失败，也不要修改第三方 SDK 的异常定义。

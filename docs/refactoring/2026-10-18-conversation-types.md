# 抽离会话辅助类型

这次在已有三层重构基础上，将会话记录接口、消息记录器、文件存储和上下文运行状态拆成独立文件。请求、响应、配置 Bean 继续保留原来的组织方式。

## 按职责阅读

| 文件 | 职责 |
| --- | --- |
| `conversation/Journal.java` | 本轮会话记录契约，供聊天应用与智能体运行服务调用 |
| `conversation/FileConversationJournal.java` | 每轮独立创建的记录器，处理消息、追问、完成、失败和取消状态 |
| `conversation/ConversationTranscriptStore.java` | 页面消息文件的路径检查、JSON 读写、原子替换及清理 |
| `service/impl/ContextMemoryState.java` | 上下文运行状态、会话锁、压缩状态和 Token 占用；仅当前包可见 |
| `service/impl/ConversationServiceImpl.java` | 会话归属、数据库元数据、运行登记和业务流程编排 |

## 启动与收尾

`ConversationService.begin` 校验参数、创建或恢复会话元数据后，创建 `FileConversationJournal`。记录器通过 `ConversationTranscriptStore` 恢复历史，不再隐式访问外部 Service 的字段和私有方法。

创建记录器时显式传入两个回调：强制保存后，`touchSession` 使用 MyBatis-Plus 更新会话时间；关闭记录器时，按实例从运行登记中移除。记录器自身没有 Service 类型依赖，也不是共享的 Spring Bean，每轮任务拥有独立实例。

正常完成与删除仍沿用原来的锁与顺序：消息保存后释放登记；删除时先关闭记录器，再在锁外取消任务，随后清理记忆及页面文件。删除后迟到的回调仍不再写入。

## 兼容范围

HTTP 路由、请求响应、JSON 历史格式、模型记忆标识、文件目录和逻辑删除规则保持原样。Java 调用方统一使用 `conversation.Journal`，原来的 `ConversationService.Journal` 引用已更新，避免保留重复接口。

验证使用原项目 Maven 配置，运行账号、会话、追问、取消、后台执行、上下文压缩及异常处理回归测试。测试替换外部模型，不连接真实 AI/MCP 服务。

完整回归的 67 项测试全部通过。三层及会话辅助包的 190 个显式方法与构造方法均具有注释，旧内部类型引用已清理；Controller、DTO/VO 和配置 Bean 与抽离前一致。`ConversationServiceImpl` 从 556 行缩减为 281 行。

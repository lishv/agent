package com.xiaoli.aiagent.service;

import com.xiaoli.aiagent.domain.vo.ContextUsageView;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/** 按应用与会话隔离模型上下文；完整页面历史由 ConversationService 管理。 */
public interface ContextMemoryService {
    String SUMMARY = "huiling.context-summary";
    String INTERNAL = "huiling.internal-prompt";

    /** 登记应用默认的系统提示、工具定义及控制提示所占 Token。 */
    void registerProfile(String app, String system, ToolCallback[] tools, String controlPrompt);

    /** 用本轮任务的实际配置更新指定会话的提示和工具 Token 开销。 */
    /**
     * 更新指定会话的提示和工具 Token 开销。
     * @param app 应用名称
     * @param chatId 会话ID
     * @param systemPrompt 系统提示
     * @param tools 工具定义数组
     * @param nextStepPrompt 下一步提示
     */
    void registerProfile(String app, String chatId, String systemPrompt, ToolCallback[] tools, String nextStepPrompt);

    /** 独占打开会话上下文并恢复历史；该会话已被任务使用时拒绝打开。 */
    List<Message> open(String app, String id);

    /** 由当前任务在工具调用结束后的安全位置准备并按需压缩上下文。 */
    List<Message> prepare(String app, String id, List<Message> messages);

    /** 过滤内部控制消息后覆盖保存历史，已删除会话忽略迟到写入。 */
    void save(String app, String id, List<Message> messages);

    /** 在持久化历史末尾追加消息并更新估算占用，已删除会话忽略写入。 */
    void append(String app, String id, List<Message> additions);

    /** 释放上下文使用状态，必要时安排后台摘要压缩。 */
    void close(String app, String id);

    /** 请求手动压缩；运行中的任务等待安全位置，空闲会话交给后台执行。 */
    ContextUsageView request(String app, String id);

    /** 返回估算 Token 占用、预算及压缩状态，不暴露记忆内容。 */
    ContextUsageView usage(String app, String id);

    /** 删除记忆及其备份，并阻止本进程内尚未结束的回调重新写入。 */
    void delete(String app, String id);

    /** 停止后台压缩执行器并中断尚未结束的任务。 */
    void shutdown();
}

package com.xiaoli.aiagent.service;

import com.xiaoli.aiagent.domain.vo.ContextUsageView;

/** 将用户拥有的聊天会话映射到应用隔离的模型上下文。 */
public interface ConversationContextService {
    /** 校验会话归属后查询模型上下文的估算占用与压缩状态。 */
    ContextUsageView usage(Long userId, String chatId);

    /** 校验会话归属后安排手动压缩，返回当前执行或排队状态。 */
    ContextUsageView compact(Long userId, String chatId);
}

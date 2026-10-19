package com.xiaoli.aiagent.service.impl;

import com.xiaoli.aiagent.domain.vo.ContextUsageView;
import com.xiaoli.aiagent.service.ContextMemoryService;
import com.xiaoli.aiagent.service.ConversationContextService;
import com.xiaoli.aiagent.service.ConversationService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

/** 会话上下文接口统一校验用户、应用和逻辑删除状态。 */
@Service
@RequiredArgsConstructor
public class ConversationContextServiceImpl implements ConversationContextService {
    private final ConversationService conversations;
    private final ContextMemoryService memory;

    /** 校验会话归属和删除状态，将页面会话映射到应用隔离的模型记忆后查询占用。 */
    @Override
    public ContextUsageView usage(Long userId, String chatId) {
        var row = conversations.own(userId, chatId, null);
        return memory.usage(row.getAppId(), conversations.memoryKey(row));
    }

    /** 校验会话归属和删除状态，再为其模型记忆请求手动压缩。 */
    @Override
    public ContextUsageView compact(Long userId, String chatId) {
        var row = conversations.own(userId, chatId, null);
        return memory.request(row.getAppId(), conversations.memoryKey(row));
    }
}

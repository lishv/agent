package com.xiaoli.aiagent.domain.dto;
public final class ChatRequests {
    private ChatRequests() { }

    /**
     * 聊天请求
     * @param message 消息内容
     * @param chatId 聊天会话ID
     * @param runId 运行ID
     */
    public record ChatRequest(String message, String chatId, String runId) { }

    /**
     * 人类回答请求
     * @param runId 运行ID
     * @param questionId 问题ID
     * @param answer 回答内容
     */
    public record HumanAnswerRequest(String runId, String questionId, String answer) { }

    /**
     * 取消运行请求
     * @param runId 运行ID
     */
    public record CancelRunRequest(String runId) { }
}

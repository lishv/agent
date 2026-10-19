package com.xiaoli.aiagent.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Flux;

/** 聊天应用编排契约；服务端负责执行生命周期，Controller 负责 HTTP 协议。 */
public interface ChatApplicationService {
    /**
     * 创建本轮记录并在服务端订阅模型输出；浏览器断开不取消后台任务。
     *
     * @param userId 用户 ID
     * @param message 消息
     * @param chatId 聊天 ID
     * @return Flux 流
     */
    Flux<String> loveStream(Long userId, String message, String chatId);

    /**
     * 准备会话记录并启动智能体；启动失败时记录错误并释放会话。
     *
     * @param userId 用户 ID
     * @param message 消息
     * @param chatId 聊天 ID
     * @param runId 运行 ID
     * @return SseEmitter 流
     */
    SseEmitter manusStream(Long userId, String message, String chatId, String runId);

    /**
     * 校验任务归属并提交智能体追问答案。
     *
     * @param userId 用户 ID
     * @param runId 运行 ID
     * @param questionId 问题 ID
     * @param answer 回答内容
     */
    void answer(Long userId, String runId, String questionId, String answer);

    /** 发出应用关闭信号，终止本服务持有的恋爱助手输出订阅。 */
    void shutdown();
}

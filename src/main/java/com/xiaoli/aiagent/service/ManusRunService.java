package com.xiaoli.aiagent.service;

import com.xiaoli.aiagent.conversation.Journal;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 智能体运行管理契约；生产入口绑定登录用户和会话记录。 */
public interface ManusRunService {
    /** 启动独立任务并自动生成记忆标识，供不绑定登录用户的内部调用使用。 */
    SseEmitter start(String message, String requestedRunId);

    /** 使用指定记忆标识启动内部任务，不绑定页面会话记录。 */
    SseEmitter start(String message, String requestedRunId, String requestedChatId);

    /** 绑定用户、记忆标识和页面记录，启动可追问、取消并恢复历史的智能体任务。 */
    SseEmitter start(
            String message,
            String requestedRunId,
            String requestedChatId,
            Long userId,
            Journal journal);

    /** 校验任务归属后提交追问答案；已结束或不属于该用户的任务拒绝操作。 */
    void answer(String runId, String questionId, String answer, Long userId);

    /** 向运行中的任务提交一次追问答案，供已完成身份校验的内部调用使用。 */
    void answer(String runId, String questionId, String answer);

    /** 校验任务归属后取消任务，未找到对应用户的任务时抛出异常。 */
    boolean cancel(String runId, Long userId);

    /** 取消内部任务；任务已结束时返回 false，取消成功时返回 true。 */
    boolean cancel(String runId);

    /** 取消全部运行任务、关闭追问等待并清空进程内运行登记。 */
    void shutdown();
}

package com.xiaoli.aiagent.service.impl;

import com.xiaoli.aiagent.app.LoveApp;
import com.xiaoli.aiagent.conversation.Journal;
import com.xiaoli.aiagent.service.ChatApplicationService;
import com.xiaoli.aiagent.service.ConversationService;
import com.xiaoli.aiagent.service.ManusRunService;
import com.xiaoli.aiagent.service.GeneratedFileService;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.UUID;

/** 编排会话归属、文件记录、AI 执行和取消；Controller 只负责 HTTP 协议。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatApplicationServiceImpl implements ChatApplicationService {
    private final LoveApp love;
    private final ManusRunService manus;
    private final ConversationService conversationService;// 会话服务
    private final Sinks.Empty<Void> shutdown = Sinks.empty();// 用于通知所有会话关闭

    /** 准备本轮记录并在服务端订阅模型输出；浏览器断开不取消任务，显式取消或停机时收尾。 */
    /**
     * 恋爱会话
     * @param userId 用户 ID
     * @param message 消息
     * @param chatId 聊天 ID
     * @return
     */
    @Override
    public Flux<String> loveStream(Long userId, String message, String chatId) {
        var journal = conversationService.createOrRestoreJournal(userId, "love", chatId, message);//用于创建或恢复会话,登记本轮记录器并写入用户消息；同一会话不能重复启动。此外message为用户输入的消息
        var cancel = Sinks.<Boolean>one();// 用于通知模型取消执行
        journal.onCancel(() -> cancel.tryEmitValue(true));//journal.onCancel(...) 注册回调
        try {
            // 每轮使用独立任务标识，身份取自登录用户；同一会话重写同名文件也不会覆盖上一轮。
            Map<String, Object> toolContext = Map.of(
                    GeneratedFileService.USER_ID, userId,
                    GeneratedFileService.RUN_ID, UUID.randomUUID().toString());
            // 服务端持有模型订阅；浏览器只订阅输出缓存，离开页面不会取消执行。
            var output =
                    love.doChatByStream(message, journal.memoryId(), toolContext)
                            .doOnNext(journal::loveChunk)
                            .doOnComplete(journal::complete)
                            .doOnError(journal::failed)
                            .takeUntilOther(cancel.asMono())
                            .takeUntilOther(shutdown.asMono())
                            .doFinally(signal -> finish(journal))
                            .cache();
            output.subscribe(chunk -> {}, error -> log.warn("恋爱会话执行失败，已记录到会话文件", error));
            return output;
        } catch (RuntimeException e) {
            failed(journal, e);
            throw e;
        }
    }

    /** 准备当前用户的会话记录并启动智能体；启动失败时保存错误状态、释放记录器。 */
    /**
     * 准备会话记录并启动智能体；启动失败时记录错误并释放会话。
     * @param userId 用户 ID
     * @param message 初始消息体
     * @param chatId 聊天 ID
     * @param runId 运行 ID
     * @return SSE 发送器
     */
    @Override
    public SseEmitter manusStream(Long userId, String message, String chatId, String runId) {
        // 准备会话记录员
        var journal = conversationService.createOrRestoreJournal(userId, "manus", chatId, message);
        try {
            return manus.start(message, runId, journal.memoryId(), userId, journal);
        } catch (RuntimeException e) {
            failed(journal, e);
            throw e;
        }
    }

    /** 将当前用户、任务与问题标识交给运行服务校验，并提交真实追问答案。 */
    @Override
    public void answer(Long userId, String runId, String questionId, String answer) {
        manus.answer(runId, questionId, answer, userId);
    }

    /** 发出停机信号，结束本服务持有的恋爱助手输出订阅。 */
    @PreDestroy
    @Override
    public void shutdown() {
        shutdown.tryEmitEmpty();
    }

    /** 记录启动失败，并在记录失败与否的两种情况下都执行收尾。 */
    private void failed(Journal journal, RuntimeException error) {
        try {
            journal.failed(error);
        } finally {
            finish(journal);
        }
    }

    /** 关闭本轮记录器；持久化失败时记录日志，避免覆盖原始执行结果。 */
    private void finish(Journal journal) {
        try {
            journal.finish();
        } catch (RuntimeException e) {
            log.error("对话记录收尾失败", e);
        }
    }
}

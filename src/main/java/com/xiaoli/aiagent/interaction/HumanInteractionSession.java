package com.xiaoli.aiagent.interaction;

import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.exception.ErrorCode;
import com.xiaoli.aiagent.exception.ThrowUtils;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** 一次任务的提问与回答通道。同步保护状态，等待回答时不持有锁。 */
public final class HumanInteractionSession {
    private final String runId; // 本次任务的 ID
    private final Duration answerTimeout; // 回答等待时间
    private Consumer<HumanQuestion> publisher; // 问题发布者
    private PendingAnswer pending;  // 待处理的问题
    private boolean closed;     // 是否已关闭
    // 回答观察者
    private BiConsumer<String, String> answerObserver = (id, answer) -> { };

    public synchronized void setAnswerObserver(BiConsumer<String, String> observer) { answerObserver = observer; }
    // 构造函数
    // 初始化任务 ID 和回答等待时间
    public HumanInteractionSession(String runId, Duration answerTimeout) {
        if (answerTimeout.isNegative() || answerTimeout.isZero()) {
            throw new IllegalArgumentException("回答等待时间必须大于 0");
        }
        this.runId = runId;
        this.answerTimeout = answerTimeout;
    }
    // 设置问题发布者
    public synchronized void setPublisher(Consumer<HumanQuestion> publisher) {
        this.publisher = publisher;
    }

    public String ask(String question) {
        ThrowUtils.throwIf(question == null || question.isBlank(), ErrorCode.PARAMS_ERROR, "question 不能为空");
        PendingAnswer request;
        Consumer<HumanQuestion> questionPublisher;
        synchronized (this) {
            if (closed) throw new CancellationException("任务已经结束");
            ThrowUtils.throwIf(publisher == null, ErrorCode.OPERATION_ERROR, "当前任务没有连接用户交互通道");
            ThrowUtils.throwIf(pending != null, ErrorCode.CONFLICT_ERROR, "请先等待上一条问题的回答");
            request = new PendingAnswer(UUID.randomUUID().toString(), new CompletableFuture<>());
            pending = request;
            questionPublisher = publisher;
        }
        try {
            // 先登记问题，再推送，保证即时提交的回答也能找到对应等待者。
            questionPublisher.accept(new HumanQuestion(runId, request.questionId(), question.trim()));
            return request.answer().get(answerTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new BusinessException(ErrorCode.TASK_TIMEOUT, ErrorCode.TASK_TIMEOUT.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("任务已取消");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CancellationException cancellation) throw cancellation;
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取用户回答失败", e.getCause());
        } finally {
            synchronized (this) {
                if (pending == request) pending = null;
            }
        }
    }

    public synchronized boolean answer(String questionId, String answer) {
        ThrowUtils.throwIf(answer == null || answer.isBlank(), ErrorCode.PARAMS_ERROR, "answer 不能为空");
        if (closed || pending == null || !pending.questionId().equals(questionId)) return false;
        if (pending.answer().isDone()) return false;
        answerObserver.accept(questionId, answer.trim());
        return pending.answer().complete(answer.trim());
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (pending != null) {
            pending.answer().completeExceptionally(new CancellationException("任务已取消或连接已关闭"));
            pending = null;
        }
    }

    public record HumanQuestion(String runId, String questionId, String question) { }
    private record PendingAnswer(String questionId, CompletableFuture<String> answer) { }
}

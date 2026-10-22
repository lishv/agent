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

/** 一次任务的提问与回答通道。同步保护状态，等待回答时不持有锁。
 * 它本身不生成问题或答案，负责的是提问和回答之间的交接
 * */
public final class HumanInteractionSession {
    private final String runId; // 本次任务的 ID
    private final Duration answerTimeout; // 回答等待时间// 追问的最长等待时间（默认 10 分钟）


    // 问题发布者,保存“发布问题时要执行的处理”。外部通过这个方法设置“问题应该如何发布”。
    private Consumer<askHumanQuestion> publisher;

    // 收到回答后需要执行的回调,用于将回答添加到用户回答列表中,并设置为已处理。回调journal::answer
    private BiConsumer<String, String> answerObserver = (questionId, answer) -> { };


    private PendingAnswer pending;  // 保存当前正在等待回答的问题。
    private boolean closed;     // 交互通道是否已经关闭。



    //初始化
    public synchronized void setAnswerObserver(BiConsumer<String, String> observer) { answerObserver = observer; }

    // 初始化任务 ID 和回答等待时间
    public HumanInteractionSession(String runId, Duration answerTimeout) {
        if (answerTimeout.isNegative() || answerTimeout.isZero()) {
            throw new IllegalArgumentException("回答等待时间必须大于 0");
        }
        this.runId = runId;
        this.answerTimeout = answerTimeout;
    }
    // 初始化问题发布者     question -> {
    //                            if (journal != null) journal.question(question);// 记录向用户追问,如果有记忆记录,则先把用户追问记录到记忆记录中
    //                            sendEvent(emitter, "human_question", question);// 然后在记录
    public synchronized void setPublisher(Consumer<askHumanQuestion> publisher) {
        this.publisher = publisher;
    }

    // 创建问题并等待回答
    public String ask(String question) {
        ThrowUtils.throwIf(question == null || question.isBlank(), ErrorCode.PARAMS_ERROR, "question 不能为空");
        //局部变量，稍后保存本次 ask() 创建的问题及等待对象。
        PendingAnswer pendingAnswer;
        //声明另一个局部变量，用来记下本次使用的问题发布回调。
        Consumer<askHumanQuestion> questionPublisher;

        synchronized (this) {
            if (closed) throw new CancellationException("任务已经结束");
            ThrowUtils.throwIf(publisher == null, ErrorCode.OPERATION_ERROR, "当前任务没有连接用户交互通道");
            ThrowUtils.throwIf(pending != null, ErrorCode.CONFLICT_ERROR, "请先等待上一条问题的回答");

            //id和尚未得到结果的等待对象
            pendingAnswer = new PendingAnswer(UUID.randomUUID().toString(), new CompletableFuture<>());
            pending = pendingAnswer;
            questionPublisher = publisher;
        }

        //发送问题，并真正开始等待
        try {
            //创建一个准备交给前端的问题对象,执行之前设置的问题发布回调，把问题交给外部处理。
            questionPublisher.accept(new askHumanQuestion(runId, pendingAnswer.questionId(), question.trim()));
            //等待这个对象得到结果,如果超时,抛出异常,如果被取消,抛出异常,回调answerObserver处理回答,然后调用answer方法,提交追问
            return pendingAnswer.answer().get(answerTimeout.toMillis(), TimeUnit.MILLISECONDS);


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
                if (pending == pendingAnswer) pending = null;
            }
        }
    }
    //接收用户在页面提交的答案
    public synchronized boolean answer(String questionId, String userAnswer) {
        ThrowUtils.throwIf(userAnswer == null || userAnswer.isBlank(), ErrorCode.PARAMS_ERROR, "userAnswer 不能为空");
        //即pendingAnswer那一步创建的等待线程
        if (closed || pending == null || !pending.questionId().equals(questionId)) return false;
        if (pending.answer().isDone()) return false;


        //调用记录回答的回调。即调用journal::answer方法,记录将用户的回答记录到记忆记录中
        answerObserver.accept(questionId, userAnswer.trim());

        return pending.answer().complete(userAnswer.trim());//把 "答案" 填入这个等待对象作为 answer() 的返回值。
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (pending != null) {
            pending.answer().completeExceptionally(new CancellationException("任务已取消或连接已关闭"));
            pending = null;
        }
    }


    /**
     * 问题记录
     * @param runId 任务ID
     * @param questionId 问题ID
     * @param question 问题内容
     */
    public record askHumanQuestion(String runId, String questionId, String question) { }

    /**
     * 待处理回答记录
     * @param questionId 问题ID
     * @param answer 一个函数式接口,用于等待回答
     */
    private record PendingAnswer(String questionId, CompletableFuture<String> answer) { }
}

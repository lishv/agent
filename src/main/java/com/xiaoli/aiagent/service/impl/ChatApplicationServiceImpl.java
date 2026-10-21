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
    private final Sinks.Empty<Void> shutdown = Sinks.empty();// 用于通知所有会话关闭,只发完成/错误信号	信号弹，只发信号不发数据
    //还需要等待shutdown.tryEmitEmpty()
    @Override
    public Flux<String> loveStream(Long userId, String message, String chatId) {
        //用于创建或恢复会话,登记本轮记录器并写入用户消息；同一会话不能重复启动。此外message为用户输入的消息
        var journal = conversationService.createOrRestoreJournal(userId, "love", chatId, message);
        //创建一个可以手动发出停止通知的对象。
        //普通 Flux	上游自动产生数据 → 下游接收
        //Sinks	外部代码主动 push 数据 → 下游接收
        //Sinks.One	Sinks.<T>one()	最多发一个值	单发手枪，扣一次扳机
        //Sinks.Many	Sinks.<T>many()	可发多个值	机枪，可连发
        //Sinks.Empty	Sinks.<T>empty()	只发完成/错误信号	信号弹，只发信号不发数据
        Sinks.One<Boolean> cancel = Sinks.<Boolean>one();//创建最多成功发出一个值的信号源
        journal.onCancel(() -> cancel.tryEmitValue(true));//journal.onCancel的绑定回调,.tryEmitValue(true) 发出取消信号
        //取出值cancel.asMono()	拿到邮箱的钥匙
        //takeUntilOther(...)	内置asMono()方法，雇一个人守着邮箱，当 tryEmitValue(true) 发出值后，Mono 把 true 推给订阅者，takeUntilOther 收到后立即终止 AI 输出流。


        try {
            // 每轮使用独立任务标识，身份取自登录用户；同一会话重写同名文件也不会覆盖上一轮。
            Map<String, Object> toolContext = Map.of(
                    GeneratedFileService.USER_ID, userId,
                    GeneratedFileService.RUN_ID, UUID.randomUUID().toString());
            // 服务端持有模型订阅；浏览器只订阅输出缓存，离开页面不会取消执行。

            Flux<String> output =
                    love.doChatByStream(message, journal.memoryId(), toolContext)//这里是因为doChatByStream方法返回的是一个Flux<String>，而doOnNext方法是用于处理每个文本片段的。
                        .doOnNext(journal::loveChunk)//将来每收到一个文本片段，就调用 journal.loveChunk,把它记录下来
                            //只是在片段经过时，把它记录下来。执行后，继续向后传递的仍然是原来的字符串 chunk，没有变成 loveReply。
                        .doOnComplete(journal::complete)// 当模型完成输出时，调用 journal.complete。
                        .doOnError(journal::failed)// 当模型输出错误时，调用 journal.failed。
                        .takeUntilOther(cancel.asMono())// 当模型取消执行或应用关闭时，停止订阅。
                        .takeUntilOther(shutdown.asMono())// 当应用关闭时，停止订阅。
                        .doFinally(signal -> finish(journal))// 当模型完成输出或应用关闭时，调用 finish(journal)。
                        .cache();// 缓存所有输出，确保浏览器可以订阅所有文本片段。
//让已经组装好的流程开始运行。
            //chunk -> {}为什么是空的？ 因为每个片段的处理已经通过 doOnNext(journal::loveChunk) 完成了，这里不需要再处理。
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
    @PreDestroy//Spring 在应用关闭时会自动执行它。
    @Override
    public void shutdown() {
        shutdown.tryEmitEmpty();// 通知所有订阅者："信号源完成了，没有值要发"
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

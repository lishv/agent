package com.xiaoli.aiagent.interaction;

import com.xiaoli.aiagent.conversation.Journal;

import com.xiaoli.aiagent.service.ManusRunService;
import com.xiaoli.aiagent.service.impl.*;
import com.xiaoli.aiagent.agent.BaseAgent;
import com.xiaoli.aiagent.agent.model.AgentState;
import com.xiaoli.aiagent.app.LoveApp;
import com.xiaoli.aiagent.service.ChatApplicationService;
import com.xiaoli.aiagent.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 不调用真实模型，验证执行与浏览器连接断开的生命周期彼此独立。 */
class BackgroundExecutionTest {
    /** 用户主动取消或服务停机都会终止模型订阅，不应把半截答案标为正常完成。 */
    @ParameterizedTest
    @ValueSource(strings = {"cancel", "shutdown"})
    void explicitCancellationAndShutdownStopLoveExecution(String action) {
        var love = mock(LoveApp.class);
        var conversations = mock(ConversationService.class);
        var journal = mock(Journal.class);
        when(conversations.begin(1L, "love", "cancel-love", "继续回答")).thenReturn(journal);
        when(journal.memoryId()).thenReturn("1_cancel-love");
        var cancel = new AtomicReference<Runnable>();
        doAnswer(call -> { cancel.set(call.getArgument(0)); return null; }).when(journal).onCancel(any());
        var source = new AtomicReference<FluxSink<String>>();
        when(love.doChatByStream(eq("继续回答"), eq("1_cancel-love"), anyMap())).thenReturn(Flux.create(source::set));
        var service = new ChatApplicationServiceImpl(love, mock(ManusRunService.class), conversations);
        service.loveStream(1L, "继续回答", "cancel-love");
        source.get().next("已收到部分内容");
        if (action.equals("cancel")) cancel.get().run(); else service.shutdown();
        assertTrue(source.get().isCancelled());
        source.get().next("迟到内容");
        verify(journal).loveChunk("已收到部分内容");
        verify(journal, never()).loveChunk("迟到内容");
        verify(journal, never()).complete();
        verify(journal).finish();
        service.shutdown();
    }

    @Test void loveContinuesAndRecordsItsResultAfterViewerDisconnects() {
        var love = mock(LoveApp.class);
        var conversations = mock(ConversationService.class);
        var journal = mock(Journal.class);
        when(conversations.begin(1L, "love", "background-love", "你好")).thenReturn(journal);
        when(journal.memoryId()).thenReturn("1_background-love");
        var source = new AtomicReference<FluxSink<String>>();
        when(love.doChatByStream(eq("你好"), eq("1_background-love"), anyMap())).thenReturn(Flux.create(source::set));
        var service = new ChatApplicationServiceImpl(love, mock(ManusRunService.class), conversations);
        var stream = service.loveStream(1L, "你好", "background-love");
        var viewer = stream.subscribe();
        source.get().next("第一段");
        viewer.dispose();
        assertFalse(source.get().isCancelled(), "离开页面不能取消模型执行");
        source.get().next("完整答案"); source.get().complete();
        verify(journal).loveChunk("第一段"); verify(journal).loveChunk("完整答案");
        verify(journal).complete(); verify(journal).finish();
    }

    @Test void manusStillProducesItsFinalAnswerAfterSseDisconnects() throws Exception {
        var started = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var finalAnswer = new AtomicReference<String>();
        var agent = new BaseAgent() {
            @Override public String step() {
                started.countDown();
                try { if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                setState(AgentState.FINISHED); return "后台执行的最终答案";
            }
        };
        agent.setName("background-test"); agent.setFinalAnswerObserver(finalAnswer::set);
        var emitter = agent.runStream("执行任务", ignored -> {}, finished::countDown);
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            @SuppressWarnings("unchecked")
            var onError = (Consumer<Throwable>) ReflectionTestUtils.getField(emitter, "errorCallback");
            onError.accept(new IOException("模拟浏览器断开连接"));
            resume.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertEquals(AgentState.FINISHED, agent.getState());
            assertEquals("后台执行的最终答案", finalAnswer.get());
        } finally { resume.countDown(); agent.cancelStream(); }
    }
}

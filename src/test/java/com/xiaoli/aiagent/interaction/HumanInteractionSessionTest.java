package com.xiaoli.aiagent.interaction;
import com.xiaoli.aiagent.exception.BusinessException;

import com.xiaoli.aiagent.tools.AskHumanTool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HumanInteractionSessionTest {
    private HumanInteractionSession session(String id, BlockingQueue<HumanInteractionSession.HumanQuestion> questions) {
        HumanInteractionSession session = new HumanInteractionSession(id, Duration.ofSeconds(5));
        session.setPublisher(questions::add);
        return session;
    }

    @Test
    void toolWaitsAndReturnsTheRealAnswer() throws Exception {
        BlockingQueue<HumanInteractionSession.HumanQuestion> questions = new LinkedBlockingQueue<>();
        HumanInteractionSession session = session("run-a", questions);
        AskHumanTool tool = new AskHumanTool(session::ask);
        CompletableFuture<AskHumanTool.HumanReply> result = CompletableFuture.supplyAsync(() -> tool.askHuman("请提供任务目标"));
        var question = questions.poll(2, TimeUnit.SECONDS);
        assertNotNull(question);
        assertFalse(result.isDone());
        assertTrue(session.answer(question.questionId(), "  整理 Java 学习路线  "));
        assertEquals("整理 Java 学习路线", result.get(2, TimeUnit.SECONDS).answer());
        assertEquals("请提供任务目标", result.get().question());
    }

    @Test
    void answersCannotCrossDifferentRuns() throws Exception {
        BlockingQueue<HumanInteractionSession.HumanQuestion> a = new LinkedBlockingQueue<>();
        BlockingQueue<HumanInteractionSession.HumanQuestion> b = new LinkedBlockingQueue<>();
        var first = session("run-a", a);
        var second = session("run-b", b);
        var resultA = CompletableFuture.supplyAsync(() -> first.ask("你的目标？"));
        var resultB = CompletableFuture.supplyAsync(() -> second.ask("你的文件？"));
        var questionA = a.poll(2, TimeUnit.SECONDS);
        var questionB = b.poll(2, TimeUnit.SECONDS);
        assertNotNull(questionA);
        assertNotNull(questionB);
        assertFalse(first.answer(questionB.questionId(), "错误的回答"));
        assertTrue(first.answer(questionA.questionId(), "学习 Java"));
        assertTrue(second.answer(questionB.questionId(), "D:/notes.txt"));
        assertEquals("学习 Java", resultA.get(2, TimeUnit.SECONDS));
        assertEquals("D:/notes.txt", resultB.get(2, TimeUnit.SECONDS));
    }

    @Test
    void eachQuestionHasANewIdAndRejectsStaleOrDuplicateAnswers() throws Exception {
        BlockingQueue<HumanInteractionSession.HumanQuestion> questions = new LinkedBlockingQueue<>();
        var session = session("run-a", questions);
        var first = CompletableFuture.supplyAsync(() -> session.ask("地点？"));
        var old = questions.poll(2, TimeUnit.SECONDS);
        assertNotNull(old);
        assertTrue(session.answer(old.questionId(), "杭州"));
        assertEquals("杭州", first.get(2, TimeUnit.SECONDS));
        assertFalse(session.answer(old.questionId(), "北京"));
        var second = CompletableFuture.supplyAsync(() -> session.ask("预算？"));
        var current = questions.poll(2, TimeUnit.SECONDS);
        assertNotNull(current);
        assertNotEquals(old.questionId(), current.questionId());
        assertFalse(session.answer(old.questionId(), "旧回答"));
        assertTrue(session.answer(current.questionId(), "1000 元"));
        assertEquals("1000 元", second.get(2, TimeUnit.SECONDS));
    }

    @Test
    void closeReleasesTheWaitingToolAndRejectsLaterAnswers() throws Exception {
        BlockingQueue<HumanInteractionSession.HumanQuestion> questions = new LinkedBlockingQueue<>();
        var session = session("run-a", questions);
        var result = CompletableFuture.supplyAsync(() -> session.ask("你的目标？"));
        var question = questions.poll(2, TimeUnit.SECONDS);
        assertNotNull(question);
        session.close();
        assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertFalse(session.answer(question.questionId(), "迟到的回答"));
    }

    @Test
    void timeoutDoesNotInventAnAnswer() {
        var session = new HumanInteractionSession("run-a", Duration.ofMillis(20));
        session.setPublisher(question -> { });
        var error = assertThrows(BusinessException.class, () -> session.ask("请补充目标"));
        assertTrue(error.getMessage().contains("超时"));
    }

    @Test
    void rejectsBlankInputAndClearsPendingQuestionWhenPublishingFails() throws Exception {
        BlockingQueue<HumanInteractionSession.HumanQuestion> questions = new LinkedBlockingQueue<>();
        var session = session("run-a", questions);
        assertThrows(BusinessException.class, () -> session.ask(" "));
        assertThrows(BusinessException.class, () -> session.answer("id", " "));
        session.setPublisher(question -> { throw new IllegalStateException("连接已关闭"); });
        assertThrows(IllegalStateException.class, () -> session.ask("目标？"));
        session.setPublisher(questions::add);
        var result = CompletableFuture.supplyAsync(() -> session.ask("新问题？"));
        var question = questions.poll(2, TimeUnit.SECONDS);
        assertNotNull(question);
        session.answer(question.questionId(), "真实答案");
        assertEquals("真实答案", result.get(2, TimeUnit.SECONDS));
    }
}

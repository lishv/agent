package com.xiaoli.aiagent.chatmemory;

import com.xiaoli.aiagent.service.impl.*;
import com.xiaoli.aiagent.config.ContextMemoryProperties;
import com.xiaoli.aiagent.service.ContextMemoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实文件与本地 tokenizer，只将模型替换为可控制的模拟模型。 */
class ContextCompactionTest {
    @TempDir Path directory;
    ContextMemoryProperties config;
    ContextTokenEstimator tokens;
    FileBasedChatMemoryRepository love, manus;
    ChatModel loveModel, manusModel;
    ContextMemoryService memory;
    @BeforeEach void setup() {
        config = new ContextMemoryProperties(); config.setAutoEnabled(false); config.setSummaryMaxTokens(128);
        tokens = new ContextTokenEstimator();
        love = new FileBasedChatMemoryRepository(directory.resolve("love").toString());
        manus = new FileBasedChatMemoryRepository(directory.resolve("manus").toString());
        loveModel = mock(ChatModel.class); manusModel = mock(ChatModel.class);
        when(loveModel.call(any(Prompt.class))).thenReturn(answer("用户目标和约束已保留；继续处理尚未完成事项。"));
        when(manusModel.call(any(Prompt.class))).thenReturn(answer("已完成检索，证据保存在 notes.txt；尚需整理结果。"));
        memory = engine();
    }
    private ContextMemoryService engine() {
        return new ContextMemoryServiceImpl(love, manus, loveModel, manusModel, config, tokens,
                new HookContextSummaryGenerator(tokens), directory.toString());
    }
    private ChatResponse answer(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    @AfterEach void stop() { memory.shutdown(); }
    private List<Message> history(int turns) {
        List<Message> result = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            result.add(new UserMessage("用户第 " + i + " 轮要求：" + "具体目标和约束。".repeat(40)));
            result.add(new AssistantMessage("回答第 " + i + " 轮：" + "已完成事项及待办。".repeat(40)));
        }
        return result;
    }
    private void awaitDone(String app, String id) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (Set.of("queued", "compressing").contains(memory.usage(app, id).status())) {
            if (System.nanoTime() > deadline) fail("压缩没有在测试期限内完成");
            Thread.sleep(10);
        }
    }
    @Test void manualCompactionRetainsFourTurnsAndRestoresSummaryAndUsageAfterRestart() throws Exception {
        var original = history(6); love.saveAll("101_room", original);
        memory.request("love", "101_room"); awaitDone("love", "101_room");
        var compacted = love.findByConversationId("101_room");
        assertEquals(9, compacted.size()); assertTrue(compacted.getFirst().getText().contains("历史记忆摘要"));
        assertEquals(original.subList(4, 12), compacted.subList(1, 9));
        var beforeRestart = memory.usage("love", "101_room");
        assertEquals("completed", beforeRestart.status()); assertTrue(beforeRestart.lastAfterTokens() < beforeRestart.lastBeforeTokens());
        try (var files = Files.list(directory.resolve("love/context-backups"))) { assertEquals(1, files.count()); }
        memory.shutdown(); memory = engine();
        assertEquals(1, memory.usage("love", "101_room").compactionCount());
        assertEquals(beforeRestart.lastAfterTokens(), memory.usage("love", "101_room").lastAfterTokens());
    }
    @Test void deletionCleansOnlyTheOwnedAppMemoryAndItsBackupsAndBlocksLateWrites() throws Exception {
        love.saveAll("101_room", history(6)); manus.saveAll("101_room", history(5)); love.saveAll("102_room", history(5));
        love.saveAll("101_room_other", history(5));
        memory.request("love", "101_room"); awaitDone("love", "101_room");
        Path backups = directory.resolve("love/context-backups");
        Path orphan = backups.resolve("101_room_" + UUID.randomUUID() + ".kryo.tmp");
        Path neighbor = backups.resolve("101_room_other_" + UUID.randomUUID() + ".kryo");
        Files.writeString(orphan, "临时文件"); Files.writeString(neighbor, "其他会话备份");
        memory.delete("love", "101_room");
        assertFalse(Files.exists(directory.resolve("love/101_room.kryo"))); assertFalse(Files.exists(orphan));
        try (var files = Files.list(backups)) { assertEquals(List.of(neighbor), files.toList()); }
        assertEquals(history(5), manus.findByConversationId("101_room"));
        assertEquals(history(5), love.findByConversationId("102_room"));
        assertEquals(history(5), love.findByConversationId("101_room_other"));
        memory.save("love", "101_room", history(6)); memory.append("love", "101_room", history(6)); memory.close("love", "101_room");
        assertFalse(Files.exists(directory.resolve("love/101_room.kryo")));
        assertThrows(com.xiaoli.aiagent.exception.BusinessException.class, () -> memory.open("love", "101_room"));
        assertThrows(com.xiaoli.aiagent.exception.BusinessException.class, () -> memory.request("love", "101_room"));
        memory.delete("love", "101_room");
    }

    @Test void deletingWhileTheSummaryModelRunsCannotRecreateMemoryOrBackups() throws Exception {
        love.saveAll("101_deleting", history(6));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(loveModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("等待超时");
            return answer("保留历史要点。");
        });
        memory.request("love", "101_deleting");
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread deleting = Thread.ofVirtual().start(() -> {
            try { memory.delete("love", "101_deleting"); } catch (Throwable error) { failure.set(error); }
        });
        try {
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (deleting.getState() != Thread.State.WAITING) {
                if (System.nanoTime() > deadline) fail("删除任务未进入上下文锁等待");
                Thread.sleep(5);
            }
        } finally { release.countDown(); }
        deleting.join(5000); assertFalse(deleting.isAlive()); assertNull(failure.get());
        memory.close("love", "101_deleting"); memory.save("love", "101_deleting", history(6));
        assertFalse(Files.exists(directory.resolve("love/101_deleting.kryo")));
        try (var files = Files.list(directory.resolve("love/context-backups"))) { assertEquals(0, files.count()); }
    }

    @Test void corruptedMemoryCanBeDeletedWithoutDeserialization() throws Exception {
        Path target = directory.resolve("love/101_corrupt.kryo");
        Files.writeString(target, "损坏的记忆文件");
        memory.delete("love", "101_corrupt");
        assertFalse(Files.exists(target));
        memory.append("love", "101_corrupt", history(6));
        assertFalse(Files.exists(target));
    }

    @Test void failedSummaryLeavesOriginalFileUnchangedAndCanBeRetriedManually() throws Exception {
        love.saveAll("101_room", history(6));
        byte[] original = Files.readAllBytes(directory.resolve("love/101_room.kryo"));
        when(loveModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("不可用"));
        memory.request("love", "101_room"); awaitDone("love", "101_room");
        assertEquals("error", memory.usage("love", "101_room").status());
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("love/101_room.kryo")));
        when(loveModel.call(any(Prompt.class))).thenReturn(answer("保留事实、关系、约束和待办。"));
        memory.request("love", "101_room"); awaitDone("love", "101_room");
        assertEquals("completed", memory.usage("love", "101_room").status());
    }
    @Test void aManualRequestDuringExecutionWaitsForASafeModelBoundary() throws Exception {
        manus.saveAll("101_room", history(6)); var running = memory.open("manus", "101_room");
        memory.request("manus", "101_room");
        assertEquals("queued", memory.usage("manus", "101_room").status()); verifyNoInteractions(manusModel);
        var prepared = memory.prepare("manus", "101_room", running);
        assertTrue(prepared.getFirst().getText().contains("历史记忆摘要"));
        assertEquals(1, memory.usage("manus", "101_room").compactionCount());
        memory.close("manus", "101_room");
    }
    @Test void appsAndUsersNeverShareSummariesOrCompressionRequests() throws Exception {
        love.saveAll("101_room", history(6)); manus.saveAll("101_room", history(5)); love.saveAll("102_room", history(5));
        var otherApp = manus.findByConversationId("101_room"); var otherUser = love.findByConversationId("102_room");
        memory.request("love", "101_room"); awaitDone("love", "101_room");
        assertEquals(otherApp, manus.findByConversationId("101_room")); assertEquals(otherUser, love.findByConversationId("102_room"));
        verifyNoInteractions(manusModel);
    }
    @Test void automaticCompressionUsesSeventyPercentAndDoesNotSplitToolCallPairs() {
        config.setAutoEnabled(true); config.getApps().get("manus").setContextWindowTokens(4096);
        config.getApps().get("manus").setResponseReserveTokens(512);
        var call = AssistantMessage.builder().content("读取文件").toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "readFile", "{\"path\":\"notes.txt\"}"))).build();
        var response = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "readFile", "大量工具内容。".repeat(1600)))).build();
        var prompt = new UserMessage("保留原始目标：分析 notes.txt");
        var prepared = memory.prepare("manus", "101_tools", List.of(prompt, call, response));
        assertEquals("completed", memory.usage("manus", "101_tools").status());
        assertTrue(prepared.contains(prompt));
        assertTrue(prepared.stream().noneMatch(ToolResponseMessage.class::isInstance));
        assertTrue(memory.usage("manus", "101_tools").usagePercent() < 70);
        verify(manusModel, atLeastOnce()).call(any(Prompt.class));
    }
    @Test void unfinishedToolCallsRemainIntactWhenOlderTurnsAreCompacted() throws Exception {
        var original = new ArrayList<>(history(6));
        var pending = AssistantMessage.builder().content("等待工具").toolCalls(List.of(new AssistantMessage.ToolCall("pending-1", "function", "askHuman", "{}"))).build();
        original.add(pending); manus.saveAll("101_pending", original);
        memory.request("manus", "101_pending"); awaitDone("manus", "101_pending");
        assertEquals(pending, manus.findByConversationId("101_pending").getLast());
        assertEquals(original.subList(4, original.size()), manus.findByConversationId("101_pending").subList(1, 10));
    }
    @Test void hookSummaryReceivesToolArgumentsAndActualResultsInsteadOfNullText() throws Exception {
        var original = new ArrayList<>(history(6));
        original.add(2, AssistantMessage.builder().toolCalls(List.of(new AssistantMessage.ToolCall(
                "evidence-call", "function", "readFile", "{\"path\":\"evidence.txt\"}"))).build());
        original.add(3, ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                "evidence-call", "readFile", "资料来源编号为 evidence-2026，仍需核对第三项事实。"))).build());
        manus.saveAll("101_evidence", original);
        memory.request("manus", "101_evidence"); awaitDone("manus", "101_evidence");
        assertEquals("completed", memory.usage("manus", "101_evidence").status());
        verify(manusModel).call(argThat((Prompt prompt) -> prompt.getUserMessage().getText().contains("<primary_objective>")
                && prompt.getUserMessage().getText().contains("evidence.txt")
                && prompt.getUserMessage().getText().contains("资料来源编号为 evidence-2026")
                && prompt.getUserMessage().getText().contains("仍需核对第三项事实")));
        assertEquals(original.subList(6, original.size()), manus.findByConversationId("101_evidence").subList(1, 9));
    }
    @Test void failureInALaterFrameworkChunkDoesNotPersistAPartialSummary() throws Exception {
        var original = history(6);
        original.set(0, new UserMessage("长历史中的必要事实。".repeat(6000)));
        love.saveAll("101_chunk_failure", original);
        byte[] before = Files.readAllBytes(directory.resolve("love/101_chunk_failure.kryo"));
        when(loveModel.call(any(Prompt.class))).thenReturn(answer("第一段成功的摘要。"))
                .thenThrow(new IllegalStateException("第二段模型调用失败"));
        memory.request("love", "101_chunk_failure"); awaitDone("love", "101_chunk_failure");
        assertEquals("error", memory.usage("love", "101_chunk_failure").status());
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("love/101_chunk_failure.kryo")));
        assertEquals(0, memory.usage("love", "101_chunk_failure").compactionCount());
        verify(loveModel, times(2)).call(any(Prompt.class));
    }
    @Test void systemAndToolDefinitionsAreIncludedInEstimatedOccupancy() {
        memory.registerProfile("manus", "系统提示".repeat(100), new org.springframework.ai.tool.ToolCallback[0], "步骤指引".repeat(100));
        var history = List.<Message>of(new UserMessage("短内容")); memory.save("manus", "101_tokens", history);
        assertTrue(memory.usage("manus", "101_tokens").estimatedTokens() > tokens.computeMessagesTokens(history));
        assertTrue(memory.usage("manus", "101_tokens").estimated());
    }
    @Test void activeSessionProfilesDoNotChangeOtherSessionsTokenOccupancy() {
        var history = List.<Message>of(new UserMessage("相同内容"));
        memory.save("love", "101_first", history); memory.save("love", "101_second", history);
        int second = memory.usage("love", "101_second").estimatedTokens();
        memory.registerProfile("love", "101_first", "长系统提示".repeat(100), new org.springframework.ai.tool.ToolCallback[0], "");
        assertTrue(memory.usage("love", "101_first").estimatedTokens() > second);
        assertEquals(second, memory.usage("love", "101_second").estimatedTokens());
    }
    @Test void loveAdvisorUsesThePersistedSummaryInTheNextRealChatClientRequest() throws Exception {
        when(loveModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            return answer(prompt.getSystemMessage().getText().contains("你正在压缩")
                    ? "用户关系和目标已保留。" : "恋爱建议及事实。".repeat(40));
        });
        var app = new com.xiaoli.aiagent.app.LoveApp(loveModel, memory);
        for (int i=0;i<6;i++) app.doChat("第 "+i+" 轮恋爱困扰："+"重要情况。".repeat(40),"101_advisor");
        assertEquals(12, love.findByConversationId("101_advisor").size());
        memory.request("love","101_advisor"); awaitDone("love","101_advisor");
        clearInvocations(loveModel);
        app.doChat("请继续根据关系背景给建议","101_advisor");
        verify(loveModel).call(argThat((Prompt prompt) -> prompt.getInstructions().stream()
                .anyMatch(message -> message.getText().contains("历史记忆摘要")) && prompt.getUserMessage().getText().equals("请继续根据关系背景给建议")));
    }
    @Test void stoppingManusDuringCompressionPreventsAnotherTaskModelCall() throws Exception {
        config.setAutoEnabled(true); config.setThreshold(.1); config.setTargetRatio(.05);
        manus.saveAll("stop-context-chat", history(6));
        var summarizing = new java.util.concurrent.CountDownLatch(1);
        var taskCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(manusModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            if (prompt.getSystemMessage().getText().contains("你正在压缩")) {
                summarizing.countDown();
                try { Thread.sleep(30000); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                return answer("摘要");
            }
            taskCalls.incrementAndGet(); return answer("不应在停止后调用");
        });
        var provider = (org.springframework.ai.tool.ToolCallbackProvider) () -> new org.springframework.ai.tool.ToolCallback[0];
        var runs = new com.xiaoli.aiagent.service.impl.ManusRunServiceImpl(new org.springframework.ai.tool.ToolCallback[0],manusModel,provider,5000,manus);
        runs.configureContexts(memory,new com.xiaoli.aiagent.agent.MyManus(new org.springframework.ai.tool.ToolCallback[0],manusModel,provider));
        try {
            runs.start("继续任务","stop-context-run","stop-context-chat");
            assertTrue(summarizing.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(runs.cancel("stop-context-run"));
            assertEquals(0, taskCalls.get());
            assertFalse(runs.cancel("stop-context-run"));
        } finally { runs.shutdown(); }
    }
    @Test void loveStreamingQueuesManualCompressionUntilTheCompleteReplyHasBeenSaved() throws Exception {
        love.saveAll("101_stream",history(6));
        var source = reactor.core.publisher.Sinks.many().unicast().<ChatResponse>onBackpressureBuffer();
        when(loveModel.stream(any(Prompt.class))).thenReturn(source.asFlux());
        var app = new com.xiaoli.aiagent.app.LoveApp(loveModel,memory);
        org.springframework.test.util.ReflectionTestUtils.setField(app, "allTools", new org.springframework.ai.tool.ToolCallback[0]);
        org.springframework.test.util.ReflectionTestUtils.setField(app, "toolCallbackProvider",
                (org.springframework.ai.tool.ToolCallbackProvider) () -> new org.springframework.ai.tool.ToolCallback[0]);
        var vectorStore = mock(org.springframework.ai.vectorstore.VectorStore.class);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());
        org.springframework.test.util.ReflectionTestUtils.setField(app, "pgVectorVectorStore", vectorStore);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var received = new StringBuilder();
        var viewer = app.doChatByStream("本轮用户的实际问题","101_stream").subscribe(received::append,
                failure -> { error.set(failure); finished.countDown(); }, finished::countDown);
        try {
            long deadline = System.nanoTime()+5_000_000_000L;
            while (love.findByConversationId("101_stream").size()!=13) {
                if(System.nanoTime()>deadline) fail("流式请求未开始"); Thread.sleep(10);
            }
            source.tryEmitNext(answer("第一段"));
            memory.request("love","101_stream");
            assertEquals("queued",memory.usage("love","101_stream").status());
            verify(loveModel,never()).call(any(Prompt.class));
            source.tryEmitNext(answer("第二段")); source.tryEmitComplete();
            assertTrue(finished.await(5,java.util.concurrent.TimeUnit.SECONDS)); assertNull(error.get());
            awaitDone("love","101_stream");
            assertEquals("第一段第二段",received.toString());
            assertEquals("第一段第二段",love.findByConversationId("101_stream").getLast().getText());
            assertEquals("completed",memory.usage("love","101_stream").status());
        } finally { viewer.dispose(); }
    }
    @Test void openAiSummariesSetACompletionLimitAndCannotExecuteTools() {
        var model = mock(org.springframework.ai.openai.OpenAiChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(answer("准确保留目标和事实。"));
        new HookContextSummaryGenerator(tokens).summarize(model,"待压缩的历史",28672,128);
        verify(model).call(argThat((Prompt prompt) -> prompt.getOptions() instanceof org.springframework.ai.openai.OpenAiChatOptions options
                && options.getMaxCompletionTokens()==128 && options.getMaxTokens()==null
                && options.getToolCallbacks().isEmpty() && options.getTools().isEmpty()
                && Boolean.FALSE.equals(options.getInternalToolExecutionEnabled())));
    }
}

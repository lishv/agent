package com.xiaoli.aiagent.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.xiaoli.aiagent.chatmemory.*;
import com.xiaoli.aiagent.config.ContextMemoryProperties;
import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.rag.QueryRewriter;
import com.xiaoli.aiagent.service.ContextMemoryService;
import com.xiaoli.aiagent.service.impl.ContextMemoryServiceImpl;
import com.xiaoli.aiagent.tools.TerminateTool;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MimeTypeUtils;

import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** 使用真实文件记忆、ChatClient 和 Advisor，检查恋爱助手各入口及失败后的恢复。 */
class LoveAppFunctionalTest {
    @TempDir Path directory;
    private ChatModel model;
    private ContextMemoryService memory;
    private FileBasedChatMemoryRepository repository;
    private VectorStore vectors;
    private LoveApp app;

    /** 模型和向量检索使用可控响应，记忆仍实际写入测试文件。 */
    @BeforeEach
    void setUp() {
        model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(DashScopeChatOptions.builder().build());
        var properties = new ContextMemoryProperties();
        properties.setAutoEnabled(false);
        var tokens = new ContextTokenEstimator();
        repository = new FileBasedChatMemoryRepository(directory.resolve("love").toString());
        memory =
                new ContextMemoryServiceImpl(
                        repository,
                        new FileBasedChatMemoryRepository(directory.resolve("manus").toString()),
                        model,
                        mock(ChatModel.class),
                        properties,
                        tokens,
                        new HookContextSummaryGenerator(tokens),
                        directory.toString());
        app = new LoveApp(model, memory);
        ReflectionTestUtils.setField(app, "allTools", new ToolCallback[0]);
        ReflectionTestUtils.setField(
                app, "toolCallbackProvider", (ToolCallbackProvider) () -> new ToolCallback[0]);
        vectors = mock(VectorStore.class);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        ReflectionTestUtils.setField(app, "pgVectorVectorStore", vectors);
    }

    /** 关闭测试用的摘要调度资源。 */
    @AfterEach
    void close() {
        memory.shutdown();
    }

    @Test
    void ordinaryChatRestoresHistoryAndKeepsOtherUsersIsolated() {
        var prompts = new ArrayList<Prompt>();
        when(model.call(any(Prompt.class)))
                .thenAnswer(
                        call -> {
                            prompts.add(call.getArgument(0));
                            return response("回复-" + prompts.size());
                        });
        assertEquals("回复-1", app.doChat("我的对象叫小明", "7_room"));
        assertEquals("回复-2", app.doChat("他叫什么？", "7_room"));
        assertTrue(
                prompts.get(1).getInstructions().stream()
                        .anyMatch(message -> "我的对象叫小明".equals(message.getText())));
        assertTrue(
                prompts.get(1).getInstructions().stream()
                        .anyMatch(message -> "回复-1".equals(message.getText())));
        assertEquals("回复-3", app.doChat("你好", "8_room"));
        assertFalse(prompts.get(2).getContents().contains("小明"));
        var restored = new FileBasedChatMemoryRepository(directory.resolve("love").toString());
        assertEquals(
                List.of("我的对象叫小明", "回复-1", "他叫什么？", "回复-2"),
                restored.findByConversationId("7_room").stream().map(Message::getText).toList());
    }

    @Test
    void structuredReportParsesTheModelJson() {
        when(model.call(any(Prompt.class)))
                .thenReturn(
                        response("{\"title\":\"小李的恋爱报告\",\"suggestions\":[\"先沟通\",\"共同安排活动\"]}"));
        var report = app.doChatWithReport("我是小李，想改善沟通", "7_report");
        assertEquals("小李的恋爱报告", report.title());
        assertEquals(List.of("先沟通", "共同安排活动"), report.suggestions());
    }

    @Test
    void mediaIsPassedToTheModelAndCanBeRestoredFromHistory() {
        var captured = new AtomicReference<Prompt>();
        when(model.call(any(Prompt.class)))
                .thenAnswer(
                        call -> {
                            captured.set(call.getArgument(0));
                            return response("图片中的情绪较轻松");
                        });
        Media media =
                new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] {1, 2, 3}));
        assertEquals(
                "图片中的情绪较轻松", app.doChatWithMedia("描述情绪", "7_media", Arrays.asList(null, media)));
        assertEquals(1, lastUser(captured.get()).getMedia().size());
        var restored = new FileBasedChatMemoryRepository(directory.resolve("love").toString());
        assertEquals(
                1,
                ((UserMessage) restored.findByConversationId("7_media").getFirst())
                        .getMedia()
                        .size());
    }

    @Test
    void emptyMediaUsesTextAndInvalidInputDoesNotReachTheModel() {
        when(model.call(any(Prompt.class))).thenReturn(response("普通文本回复"));
        assertEquals("普通文本回复", app.doChatWithMedia("你好", "7_text", null));
        assertThrows(BusinessException.class, () -> app.doChatWithMedia(" ", "7_text", List.of()));
        assertThrows(BusinessException.class, () -> app.doChatWithMedia("你好", " ", List.of()));
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void streamingRagInjectsKnowledgeButDoesNotPersistItAsTheUsersMessage() {
        when(vectors.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("沟通建议：先复述对方的感受，再表达自己的需求。")));
        var captured = new AtomicReference<Prompt>();
        when(model.stream(any(Prompt.class)))
                .thenAnswer(
                        call -> {
                            captured.set(call.getArgument(0));
                            return Flux.just(response("先倾听感受，"), response("再表达需求。"));
                        });
        assertEquals("先倾听感受，再表达需求。", stream("如何沟通？", "7_rag"));
        assertTrue(captured.get().getContents().contains("先复述对方的感受"));
        verify(vectors)
                .similaritySearch(
                        argThat((SearchRequest request) -> request.getQuery().contains("如何沟通？")));
        var stored = repository.findByConversationId("7_rag");
        assertEquals("如何沟通？", stored.getFirst().getText());
        assertEquals("先倾听感受，再表达需求。", stored.getLast().getText());
    }

    /** 地点策划仍能使用实时工具，知识库命中不能把用户任务限制成只回答情感资料。 */
    @Test
    void datePlanningKeepsTheUserGoalAndAllowsToolEvidenceAlongsideRag() {
        String request = "合肥，预算520元，喜好未知，暧昧期，请搜索周边并生成约会PDF";
        when(vectors.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("关系建议：先了解对方的感受。")));
        var captured = new AtomicReference<Prompt>();
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            captured.set(call.getArgument(0));
            return Flux.just(response("开始查询地点"));
        });

        assertEquals("开始查询地点", stream(request, "7_date"));
        String user = lastUser(captured.get()).getText();
        assertTrue(user.contains(request));
        assertTrue(user.contains("先了解对方的感受"));
        assertFalse(user.contains("not prior knowledge"), user);
        assertTrue(user.contains("工具结果"), user);
        String system = captured.get().getSystemMessage().getText();
        assertTrue(system.contains("maps_text_search"), system);
        assertTrue(system.contains("maps_around_search"), system);
        assertTrue(system.contains("maps_search_detail"), system);
        assertTrue(system.contains("纯文字 PDF"), system);
        assertEquals(request, repository.findByConversationId("7_date").getFirst().getText());
    }

    /** 没有知识库命中时仍保留原请求及地点检索规则，不要求用户改问知识库问题。 */
    @Test
    void emptyRagDoesNotForbidSearchingForDateLocations() {
        var captured = new AtomicReference<Prompt>();
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            captured.set(call.getArgument(0));
            return Flux.just(response("查询合肥地点"));
        });
        stream("搜索合肥周边", "7_empty_date");
        String user = lastUser(captured.get()).getText();
        assertFalse(user.contains("If the answer is not in the context"), user);
        assertTrue(user.contains("搜索合肥周边"));
        assertTrue(captured.get().getSystemMessage().getText().contains("maps_text_search"));
    }

    @Test
    void retrievalFailureSkipsTheModelAndTheConversationCanRetry() {
        when(vectors.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("embedding unavailable"));
        assertThrows(RuntimeException.class, () -> stream("继续沟通", "7_retry"));
        verify(model, never()).stream(any(Prompt.class));
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("已恢复对话")));
        assertEquals("已恢复对话", stream("重新尝试", "7_retry"));
        assertFalse(
                repository.findByConversationId("7_retry").stream()
                        .anyMatch(message -> message.getText().contains("embedding unavailable")));
    }

    @Test
    void partialStreamFailureKeepsEarlierHistoryAndAllowsANewTurn() {
        when(model.call(any(Prompt.class))).thenReturn(response("已有完整答案"));
        app.doChat("第一轮", "7_failure");
        when(model.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.concat(
                                Flux.just(response("半截回答")),
                                Flux.error(new IllegalStateException("upstream failed"))));
        assertThrows(RuntimeException.class, () -> stream("第二轮", "7_failure"));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("重试后的完整回答")));
        assertEquals("重试后的完整回答", stream("第三轮", "7_failure"));
        var stored = repository.findByConversationId("7_failure");
        assertTrue(stored.stream().anyMatch(message -> "已有完整答案".equals(message.getText())));
        assertFalse(stored.stream().anyMatch(message -> "半截回答".equals(message.getText())));
        assertEquals("重试后的完整回答", stored.getLast().getText());
    }

    @Test
    void queryRewriteAndSynchronousMcpAndLocalToolsReachTheirConfiguredEntryPoints() {
        var rewriter = mock(QueryRewriter.class);
        when(rewriter.doQueryRewrite("婚后沟通")).thenReturn("改善婚后沟通的方法");
        ReflectionTestUtils.setField(app, "queryRewriter", rewriter);
        when(model.call(any(Prompt.class)))
                .thenAnswer(
                        call -> {
                            assertEquals("改善婚后沟通的方法", lastUser(call.getArgument(0)).getText());
                            return response("先安排固定交流时间");
                        });
        assertEquals("先安排固定交流时间", app.doChatWithRag("婚后沟通", "7_rewrite"));
        ToolCallback[] tools = ToolCallbacks.from(new TerminateTool());
        when(model.call(any(Prompt.class)))
                .thenAnswer(
                        call -> {
                            Prompt prompt = call.getArgument(0);
                            var result =
                                    ToolCallingManager.builder()
                                            .build()
                                            .executeToolCalls(
                                                    prompt,
                                                    new ChatResponse(
                                                            List.of(
                                                                    new Generation(
                                                                            AssistantMessage
                                                                                    .builder()
                                                                                    .content("")
                                                                                    .toolCalls(
                                                                                            List.of(
                                                                                                    new AssistantMessage
                                                                                                            .ToolCall(
                                                                                                            "end-call",
                                                                                                            "function",
                                                                                                            "doTerminate",
                                                                                                            "{}")))
                                                                                    .build()))));
                            assertTrue(
                                    ((ToolCallingChatOptions) prompt.getOptions())
                                            .getToolCallbacks().stream()
                                                    .anyMatch(
                                                            tool ->
                                                                    tool.getToolDefinition()
                                                                            .name()
                                                                            .equals(
                                                                                    "doTerminate")));
                            return response(
                                    ((ToolResponseMessage) result.conversationHistory().getLast())
                                            .getResponses()
                                            .getFirst()
                                            .responseData());
                        });
        ReflectionTestUtils.setField(app, "allTools", tools);
        assertFalse(app.doChatWithTools("完成任务", "7_local").isBlank());
        ReflectionTestUtils.setField(
                app, "toolCallbackProvider", (ToolCallbackProvider) () -> tools);
        assertFalse(app.doChatWithMcp("调用 MCP 工具", "7_mcp").isBlank());
    }

    /** 聚合客户端实际收到的流式文本，不依赖外部模型生成的措辞。 */
    private String stream(String message, String id) {
        return String.join(
                "", app.doChatByStream(message, id).collectList().block(Duration.ofSeconds(5)));
    }

    /** 从请求中获取最后一条用户消息，避免将系统提示误当作业务输入。 */
    private UserMessage lastUser(Prompt prompt) {
        return (UserMessage)
                prompt.getInstructions().stream()
                        .filter(UserMessage.class::isInstance)
                        .reduce((first, second) -> second)
                        .orElseThrow();
    }

    /** 模拟已完成的模型回复，保留真实记忆与 Advisor 流程。 */
    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}

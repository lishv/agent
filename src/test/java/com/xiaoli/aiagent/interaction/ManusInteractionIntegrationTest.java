package com.xiaoli.aiagent.interaction;

import com.xiaoli.aiagent.conversation.Journal;
import com.xiaoli.aiagent.constant.ChatConstant;

import com.xiaoli.aiagent.service.ManusRunService;
import com.xiaoli.aiagent.service.impl.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoli.aiagent.controller.AiController;
import com.xiaoli.aiagent.controller.ConversationController;
import com.xiaoli.aiagent.service.AccountService;
import com.xiaoli.aiagent.service.ConversationService;
import com.xiaoli.aiagent.service.ConversationContextService;
import com.xiaoli.aiagent.service.ContextMemoryService;
import com.xiaoli.aiagent.agent.MyManus;
import com.xiaoli.aiagent.domain.entity.AppUser;
import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.exception.ErrorCode;
import com.xiaoli.aiagent.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.xiaoli.aiagent.chatmemory.FileBasedChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.openai.OpenAiChatOptions;
import com.xiaoli.aiagent.tools.TerminateTool;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.ArrayList;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真正执行工具和 SSE Controller，只替换模型，不请求外部 AI 或 MCP。 */
class ManusInteractionIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private ManusRunService service;
    private AccountService accounts;
    private ConversationService conversations;
    private Map<String, Runnable> cancellations;
    private ChatModel model;
    private AtomicReference<String> toolAnswer;
    @TempDir Path memoryDirectory;

    @BeforeEach
    void setUp() {
        model = mock(ChatModel.class);
        var provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        service = new ManusRunServiceImpl(new ToolCallback[0], model, provider, 5000,
                new FileBasedChatMemoryRepository(memoryDirectory.toString()));
        var controller = controllerFor(mock(com.xiaoli.aiagent.app.LoveApp.class));
        mvc = mvcFor(controller);
        toolAnswer = new AtomicReference<>();
        var calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            if (calls.getAndIncrement() == 0) {
                var message = AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("ask-1", "function", "askHuman",
                                "{\"question\":\"你希望我完成什么任务？\"}"))).build();
                return new ChatResponse(List.of(new Generation(message)));
            }
            prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                    .filter(response -> response.name().equals("askHuman"))
                    .forEach(response -> toolAnswer.set(response.responseData()));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("已根据你的真实回答整理 Java 学习路线。"))));
        });
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    @Test
    void acceptsLongChineseMessageInPostBodyAndKeepsConversationId() throws Exception {
        String message = "中".repeat(ChatConstant.MAX_INPUT_LENGTH);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("已读取长文本")))));
        MvcResult result = mvc.perform(post("/ai/manus/chat").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("message", message, "runId", "long-post-run", "chatId", "long-post-chat"))))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertTrue(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("已读取长文本"));
        verify(model).call(argThat((Prompt prompt) -> prompt.getInstructions().stream()
                .anyMatch(item -> message.equals(item.getText()))));
    }

    /** 经运行服务和 Spring AI 工具执行器验证身份确实传入后台任务，下载地址来自工具真实结果。 */
    @Test
    void backgroundToolsReceiveTheAuthenticatedUserAndRunContext() throws Exception {
        var properties = new com.xiaoli.aiagent.config.StorageProperties();
        properties.setBaseDir(memoryDirectory.toString());
        var files = new GeneratedFileServiceImpl(properties, "/api", memoryDirectory.resolve("memory").toString());
        var callbacks = ToolCallbacks.from(new com.xiaoli.aiagent.tools.FileOperationTool(memoryDirectory.toString(), files));
        service.shutdown();
        service = new ManusRunServiceImpl(callbacks, model, () -> new ToolCallback[0], 5000,
                new FileBasedChatMemoryRepository(memoryDirectory.resolve("memory").toString()));
        mvc = mvcFor(controllerFor(mock(com.xiaoli.aiagent.app.LoveApp.class)));
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("file-call", "function", "writeFile",
                                "{\"fileName\":\"result.txt\",\"content\":\"后台生成\"}"))).build()))))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    String toolResult = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast).flatMap(item -> item.getResponses().stream())
                            .filter(item -> item.name().equals("writeFile")).map(ToolResponseMessage.ToolResponse::responseData)
                            .findFirst().orElseThrow();
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(toolResult))));
                });
        var run = startWithChat("authenticated-file-run", "file-chat", "写入并下载文件");
        run.getAsyncResult(5000);
        var response = mvc.perform(asyncDispatch(run)).andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("/api/files/file/authenticated-file-run?name=result.txt"));
        assertEquals("后台生成", java.nio.file.Files.readString(memoryDirectory.resolve("file/1/authenticated-file-run/result.txt")));
        assertThrows(BusinessException.class, () -> files.requireDownload(2L, "file", "authenticated-file-run", "result.txt"));
    }

    @Test
    void lovePostStreamsAndRejectsInvalidInput() throws Exception {
        var love = mock(com.xiaoli.aiagent.app.LoveApp.class);
        var controller = controllerFor(love);
        var loveMvc = mvcFor(controller);
        String message = "中".repeat(ChatConstant.MAX_INPUT_LENGTH);
        when(love.doChatByStream(eq(message), eq("love-post-chat"), anyMap())).thenReturn(reactor.core.publisher.Flux.just("第一段", "第二段"));
        MvcResult result = loveMvc.perform(post("/ai/love_app/chat/sse").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("message", message, "chatId", "love-post-chat"))))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        loveMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertTrue(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("第二段"));
        for (String path : List.of("/ai/love_app/chat/sse", "/ai/manus/chat")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\" \",\"chatId\":\"test\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("message", "中".repeat(ChatConstant.MAX_INPUT_LENGTH + 1), "chatId", "test"))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void loveReportsErrorsDuringSseWithoutLeakingInternalDetails() throws Exception {
        var love = mock(com.xiaoli.aiagent.app.LoveApp.class);
        var loveMvc = mvcFor(controllerFor(love));
        when(love.doChatByStream(eq("继续"), eq("error-chat"), anyMap())).thenReturn(reactor.core.publisher.Flux.concat(
                reactor.core.publisher.Flux.just("第一段"),
                reactor.core.publisher.Flux.error(new IllegalStateException("internal-api-key-and-url"))));
        var result = loveMvc.perform(post("/ai/love_app/chat/sse").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"继续\",\"chatId\":\"error-chat\"}").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        var response = loveMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("第一段"), body);
        assertTrue(body.contains("event:error"), body);
        assertTrue(body.contains("服务暂时不可用"), body);
        assertFalse(body.contains("internal-api-key"), body);
        loveMvc.perform(post("/ai/love_app/chat/sse").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\" \"}").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(40000));
    }

    private MockMvc mvcFor(AiController controller) {
        // standalone MockMvc 默认 ISO-8859-1；与本项目 Spring Boot 的 UTF-8 配置保持一致。
        return MockMvcBuilders.standaloneSetup(controller,
                new ConversationController(conversations, accounts, mock(ConversationContextService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(
                new StringHttpMessageConverter(StandardCharsets.UTF_8),
                new MappingJackson2HttpMessageConverter(json)).build();
    }

    private AiController controllerFor(com.xiaoli.aiagent.app.LoveApp love) {
        accounts = mock(AccountService.class);
        var user = new AppUser(); user.setId(1L);
        when(accounts.requireUser(any())).thenReturn(user);
        conversations = mock(ConversationService.class);
        cancellations = new java.util.concurrent.ConcurrentHashMap<>();
        when(conversations.cancel(eq(1L), anyString())).thenAnswer(invocation -> {
            Runnable cancel = cancellations.remove(invocation.getArgument(1));
            if (cancel == null) return false;
            cancel.run(); return true;
        });
        when(conversations.createOrRestoreJournal(anyLong(), anyString(), org.mockito.ArgumentMatchers.nullable(String.class), anyString())).thenAnswer(invocation -> {
            String message = invocation.getArgument(3);
            if (message.isBlank() || message.length() > ChatConstant.MAX_INPUT_LENGTH) throw new BusinessException(ErrorCode.PARAMS_ERROR);
            String id = invocation.getArgument(2);
            if (id == null) id = java.util.UUID.randomUUID().toString();
            var journal = mock(Journal.class);
            when(journal.memoryId()).thenReturn(id); when(journal.chatId()).thenReturn(id);
            String chatId = id;
            doAnswer(callback -> { cancellations.put(chatId, callback.getArgument(0)); return null; }).when(journal).onCancel(any(Runnable.class));
            doAnswer(callback -> { cancellations.remove(chatId); return null; }).when(journal).finish();
            return journal;
        });
        return new AiController(new ChatApplicationServiceImpl(love, service, conversations), accounts);
    }

    private MvcResult start(String runId) throws Exception {
        return mvc.perform(post("/ai/manus/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("message", "帮我完成任务", "runId", runId))))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private MvcResult startWithChat(String runId, String chatId, String message) throws Exception {
        return mvc.perform(post("/ai/manus/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("message", message, "runId", runId, "chatId", chatId))))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private JsonNode awaitQuestion(MvcResult result) throws Exception {
        var pattern = Pattern.compile("event:human_question\\s+data:(\\{[^\\n]*})");
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            var match = pattern.matcher(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            if (match.find()) return json.readTree(match.group(1));
            Thread.sleep(10);
        }
        fail("没有收到 human_question SSE：" + result.getResponse().getContentAsString());
        return null;
    }

    @Test
    void askAnswerAndContinueWithinTheSameExecution() throws Exception {
        var run = start("test-run-123");
        var question = awaitQuestion(run);
        assertEquals("test-run-123", question.get("runId").asText());
        assertEquals("你希望我完成什么任务？", question.get("question").asText());
        mvc.perform(post("/ai/manus/answer").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runId", "test-run-123",
                                "questionId", question.get("questionId").asText(),
                                "answer", "中".repeat(ChatConstant.MAX_INPUT_LENGTH + 1)))))
                .andExpect(status().isBadRequest());
        String prefix = "整理 Java 学习路线";
        String fullAnswer = prefix + "中".repeat(ChatConstant.MAX_INPUT_LENGTH - prefix.length());
        mvc.perform(post("/ai/manus/answer").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runId", "test-run-123",
                                "questionId", question.get("questionId").asText(), "answer", fullAnswer))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        run.getAsyncResult(5000);
        var completed = mvc.perform(asyncDispatch(run)).andExpect(status().isOk()).andReturn();
        assertTrue(toolAnswer.get().contains("整理 Java 学习路线"));
        String receivedAnswer = json.readTree(toolAnswer.get()).get("answer").asText();
        assertEquals(ChatConstant.MAX_INPUT_LENGTH, receivedAnswer.length());
        assertTrue(fullAnswer.equals(receivedAnswer), "20000 字符的补充回答应完整传入工具结果");
        assertFalse(toolAnswer.get().contains("300 元"));
        assertTrue(completed.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("已根据你的真实回答"),
                completed.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(completed.getResponse().getContentAsString().contains("event:complete"));
        assertFalse(service.cancel("test-run-123"), "完成后应清理任务注册表");
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void rejectsWrongQuestionThenAcceptsTheCorrectAnswer() throws Exception {
        var run = start("test-run-234");
        var question = awaitQuestion(run);
        mvc.perform(post("/ai/manus/answer").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runId", "test-run-234", "questionId", "wrong-id", "answer", "错误回答"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/ai/manus/answer").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runId", "test-run-234", "questionId", question.get("questionId").asText(), "answer", "正确目标"))))
                .andExpect(status().isOk());
        run.getAsyncResult(5000);
        assertTrue(toolAnswer.get().contains("正确目标"));
        assertFalse(toolAnswer.get().contains("错误回答"));
    }

    @Test
    void cancelReleasesAPendingQuestionAndRejectsLateAnswers() throws Exception {
        var run = startWithChat("test-run-345", "cancel-chat", "帮我完成任务");
        var question = awaitQuestion(run);
        mvc.perform(post("/chats/cancel-chat/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(true));
        run.getAsyncResult(5000);
        mvc.perform(post("/ai/manus/answer").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runId", "test-run-345", "questionId", question.get("questionId").asText(), "answer", "迟到回答"))))
                .andExpect(status().isGone());
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void validatesRequestAndDuplicateRunIds() throws Exception {
        mvc.perform(post("/ai/manus/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\" \"}")).andExpect(status().isBadRequest());
        var run = start("test-run-456");
        var question = awaitQuestion(run);
        mvc.perform(post("/ai/manus/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("message", "新的任务", "runId", "test-run-456"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/ai/manus/answer").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runId", "test-run-456", "questionId", question.get("questionId").asText(), "answer", " "))))
                .andExpect(status().isBadRequest());
        service.cancel("test-run-456");
    }

    /** 上下文恢复失败后，同一任务和会话应能立即重试，且不会关闭未成功打开的上下文。 */
    @Test
    void failedContextPreparationReleasesRunAndChatForRetry() throws Exception {
        String runId = "context-retry-run";
        String chatId = "context-retry-chat";
        var contexts = mock(ContextMemoryService.class);
        when(contexts.open("manus", chatId))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "上下文暂时无法读取"))
                .thenReturn(new ArrayList<>());
        when(contexts.prepare(eq("manus"), eq(chatId), anyList()))
                .thenAnswer(invocation -> new ArrayList<>(invocation.<List<org.springframework.ai.chat.messages.Message>>getArgument(2)));
        ((ManusRunServiceImpl) service).configureContexts(contexts,
                new MyManus(new ToolCallback[0], model, () -> new ToolCallback[0]));
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("重试成功")))));

        mvc.perform(post("/ai/manus/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "message", "恢复任务", "runId", runId, "chatId", chatId))))
                .andExpect(status().isServiceUnavailable());
        assertFalse(service.cancel(runId));
        verify(contexts, never()).close("manus", chatId);

        var retried = startWithChat(runId, chatId, "恢复任务");
        retried.getAsyncResult(5000);
        var completed = mvc.perform(asyncDispatch(retried)).andExpect(status().isOk()).andReturn();
        assertTrue(completed.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("重试成功"));
        assertFalse(service.cancel(runId));
        verify(contexts, times(2)).open("manus", chatId);
        verify(contexts).close("manus", chatId);
    }

    @Test
    void unansweredQuestionTimesOutAndDoesNotContinueWithAnInventedAnswer() throws Exception {
        service.shutdown();
        service = new ManusRunServiceImpl(new ToolCallback[0], model, () -> new ToolCallback[0], 50,
                new FileBasedChatMemoryRepository(memoryDirectory.toString()));
        var controller = controllerFor(mock(com.xiaoli.aiagent.app.LoveApp.class));
        mvc = mvcFor(controller);
        var run = start("test-run-timeout");
        awaitQuestion(run);
        run.getAsyncResult(5000);
        var result = mvc.perform(asyncDispatch(run)).andReturn();
        String content = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("event:error"), content);
        assertTrue(content.contains("超时"), content);
        assertFalse(content.contains("已根据你的真实回答"));
        verify(model, times(1)).call(any(Prompt.class));
        assertFalse(service.cancel("test-run-timeout"));
    }

    @Test
    void loadsFileMemoryAfterServiceRestartAndKeepsOtherChatsSeparate() throws Exception {
        List<Prompt> prompts = new ArrayList<>();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            prompts.add(invocation.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("我们继续学习 Java。"))));
        });
        var first = startWithChat("memory-run-first", "memory-chat", "我叫小李，我想学习 Java");
        first.getAsyncResult(5000);
        service.shutdown();
        service = new ManusRunServiceImpl(new ToolCallback[0], model, () -> new ToolCallback[0], 5000,
                new FileBasedChatMemoryRepository(memoryDirectory.toString()));
        var controller = controllerFor(mock(com.xiaoli.aiagent.app.LoveApp.class));
        mvc = mvcFor(controller);
        var second = startWithChat("memory-run-second", "memory-chat", "继续");
        second.getAsyncResult(5000);
        assertTrue(prompts.get(1).getInstructions().stream().anyMatch(item -> item instanceof UserMessage && item.getText().contains("小李")));
        assertTrue(prompts.get(1).getInstructions().stream().anyMatch(item -> item instanceof AssistantMessage && item.getText().equals("我们继续学习 Java。")));
        var other = startWithChat("memory-run-other", "other-chat", "你好");
        other.getAsyncResult(5000);
        assertFalse(prompts.get(2).getInstructions().stream().anyMatch(item -> item.getText() != null && item.getText().contains("小李")));
        var saved = new FileBasedChatMemoryRepository(memoryDirectory.toString()).findByConversationId("memory-chat");
        assertEquals(4, saved.size());
        assertEquals("继续", saved.get(2).getText());
    }

    @Test
    void persistsHumanAnswersAndFullToolHistoryWithoutInternalStepPrompts() throws Exception {
        var run = startWithChat("memory-human-run", "memory-human-chat", "帮我完成任务");
        var question = awaitQuestion(run);
        mvc.perform(post("/ai/manus/answer").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("runId", "memory-human-run", "questionId", question.get("questionId").asText(), "answer", "学习 Java"))))
                .andExpect(status().isOk());
        run.getAsyncResult(5000);
        var saved = new FileBasedChatMemoryRepository(memoryDirectory.toString()).findByConversationId("memory-human-chat");
        assertEquals(4, saved.size());
        assertInstanceOf(AssistantMessage.class, saved.get(1));
        assertEquals("askHuman", ((AssistantMessage) saved.get(1)).getToolCalls().getFirst().name());
        assertInstanceOf(ToolResponseMessage.class, saved.get(2));
        assertTrue(((ToolResponseMessage) saved.get(2)).getResponses().getFirst().responseData().contains("学习 Java"));
        assertTrue(saved.stream().noneMatch(item -> item.getText() != null && item.getText().contains("Based on user needs")));
    }

    @Test
    void rejectsOverlappingTasksForTheSameChatAndInvalidChatIds() throws Exception {
        mvc.perform(post("/ai/manus/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("message", "你好", "chatId", "../love/another"))))
                .andExpect(status().isBadRequest());
        var run = startWithChat("memory-active-first", "active-chat", "帮我完成任务");
        awaitQuestion(run);
        mvc.perform(post("/ai/manus/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("message", "另一个任务", "runId", "memory-active-second", "chatId", "active-chat"))))
                .andExpect(status().isConflict());
        service.cancel("memory-active-first");
        run.getAsyncResult(5000);
        var next = startWithChat("memory-active-third", "active-chat", "继续");
        next.getAsyncResult(5000);
    }

    @Test
    void removedLegacyEndpointsAreUnavailable() throws Exception {
        for (String path : List.of("/ai/manus/chat", "/ai/love_app/chat/sse")) {
            mvc.perform(get(path).param("message", "你好")).andExpect(status().isMethodNotAllowed());
        }
        for (String path : List.of("/ai/love_app/chat/sync", "/ai/love_app/chat/server_sent_event", "/ai/love_app/chat/sse_emitter")) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        mvc.perform(post("/ai/manus/cancel").contentType(MediaType.APPLICATION_JSON)
                .content("{\"runId\":\"old-run\"}")).andExpect(status().isNotFound());
        verifyNoInteractions(model);
    }

    private void useTerminateTool() {
        service.shutdown();
        var provider=mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        service=new ManusRunServiceImpl(ToolCallbacks.from(new TerminateTool()),model,provider,5000,
                new FileBasedChatMemoryRepository(memoryDirectory.toString()));
        mvc=mvcFor(controllerFor(mock(com.xiaoli.aiagent.app.LoveApp.class)));
    }
    private ChatResponse termination(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text)
                .toolCalls(List.of(new AssistantMessage.ToolCall("end-1","function","doTerminate","{}"))).build())));
    }
    @Test void preservesFinalAnswerAccompanyingTerminationTool() throws Exception {
        useTerminateTool();
        String answer="建议先学习 Java 基础，再完成一个 Spring Boot 项目。";
        when(model.call(any(Prompt.class))).thenReturn(termination(answer),new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
        var run=startWithChat("final-text-run","final-text-chat","整理 Java 学习路线");
        run.getAsyncResult(5000);
        String body=run.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("建议先学习 Java 基础"),body);
        assertTrue(body.contains("event:final_answer"),body);
        assertFalse(body.contains("data:Step"));
        assertFalse(body.contains("工具 doTerminate"));
        assertTrue(body.contains("event:complete"));
        verify(model,times(2)).call(any(Prompt.class));
        verify(model).call(argThat((Prompt prompt)->prompt.getInstructions().stream()
                .anyMatch(item->item instanceof AssistantMessage && answer.equals(item.getText()))));
    }
    @Test void generatesMissingFinalAnswerWithToolsDisabledAndSavesItBeforeCompletion() throws Exception {
        useTerminateTool();
        var prompts=new ArrayList<Prompt>();
        when(model.call(any(Prompt.class))).thenAnswer(invocation->{
            Prompt prompt=invocation.getArgument(0); prompts.add(prompt);
            return prompts.size()==1 ? termination("") : new ChatResponse(List.of(new Generation(new AssistantMessage("推荐路线：基础语法、集合、数据库和 Spring Boot。"))));
        });
        var run=startWithChat("final-summary-run","final-summary-chat","给我学习路线");
        run.getAsyncResult(5000);
        String body=run.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("推荐路线：基础语法"),body);
        assertTrue(body.contains("event:final_answer"),body);
        assertTrue(body.indexOf("推荐路线")<body.indexOf("event:complete"));
        assertEquals(2,prompts.size());
        var options=(OpenAiChatOptions)prompts.get(1).getOptions();
        assertEquals("none",options.getToolChoice()); assertTrue(options.getToolCallbacks().isEmpty());
        assertTrue(prompts.get(1).getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance));
        var saved=new FileBasedChatMemoryRepository(memoryDirectory.toString()).findByConversationId("final-summary-chat");
        assertEquals("推荐路线：基础语法、集合、数据库和 Spring Boot。",saved.getLast().getText());
    }
    @Test void failedFinalAnswerIsReportedAsErrorInsteadOfSuccessfulTermination() throws Exception {
        useTerminateTool();
        when(model.call(any(Prompt.class))).thenReturn(termination(""))
                .thenThrow(new IllegalStateException("final answer unavailable"));
        var run=startWithChat("final-error-run","final-error-chat","整理任务结果");
        run.getAsyncResult(5000);
        String body=run.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("event:error"),body);
        assertFalse(body.contains("event:complete"));
        assertFalse(body.contains("工具 doTerminate"));
    }
    @Test void completionProgressDoesNotReplaceUserFacingAnswer() throws Exception {
        useTerminateTool();
        when(model.call(any(Prompt.class))).thenReturn(termination("已读取搜索结果，现在结束任务。"),
                new ChatResponse(List.of(new Generation(new AssistantMessage("根据检索结果，推荐优先阅读官方指南，并通过项目练习。")))));
        var run=startWithChat("progress-final-run","progress-final-chat","提供学习建议");
        run.getAsyncResult(5000);
        String body=run.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("推荐优先阅读官方指南"),body);
        assertFalse(body.contains("现在结束任务"));
        assertTrue(body.indexOf("推荐优先阅读")<body.indexOf("event:complete"));
    }
}

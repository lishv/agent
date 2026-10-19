package com.xiaoli.aiagent.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.*;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeApiSpec.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoli.aiagent.config.StorageProperties;
import com.xiaoli.aiagent.service.*;
import com.xiaoli.aiagent.service.impl.GeneratedFileServiceImpl;
import com.xiaoli.aiagent.tools.FileOperationTool;

import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** 使用真实 DashScopeChatModel 执行工具与后续回复，避免仅模拟 ChatModel 而遗漏适配器行为。 */
class LoveDashScopeToolExecutionTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void actualDashScopeStreamingLoopPassesContextAndReturnsTheDownloadResult() throws Exception {
        var properties = new StorageProperties();
        properties.setBaseDir(directory.toString());
        var files =
                new GeneratedFileServiceImpl(
                        properties, "/api", directory.resolve("memory").toString());
        var api = mock(DashScopeApi.class);
        var function =
                Map.of(
                        "name",
                        "writeFile",
                        "arguments",
                        "{\"fileName\":\"advice.txt\",\"content\":\"先沟通\"}");
        when(api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(
                        Flux.just(
                                chunk(
                                        "tool_calls",
                                        "",
                                        List.of(
                                                Map.of(
                                                        "id",
                                                        "call-1",
                                                        "type",
                                                        "function",
                                                        "function",
                                                        function)))))
                .thenReturn(
                        Flux.just(
                                chunk(
                                        "stop",
                                        "文件已生成：[下载"
                                            + " advice.txt](/api/files/file/love-sdk-run?name=advice.txt)",
                                        List.of())));
        var model =
                new DashScopeChatModel(
                        api,
                        DashScopeChatOptions.builder().build(),
                        ToolCallingManager.builder().build(),
                        RetryTemplate.defaultInstance(),
                        ObservationRegistry.NOOP);
        var memory = mock(ContextMemoryService.class);
        when(memory.open(eq("love"), anyString())).thenAnswer(call -> new ArrayList<Message>());
        when(memory.prepare(eq("love"), anyString(), anyList()))
                .thenAnswer(call -> call.getArgument(2));
        var app = new LoveApp(model, memory);
        ReflectionTestUtils.setField(
                app,
                "allTools",
                ToolCallbacks.from(new FileOperationTool(directory.toString(), files)));
        ReflectionTestUtils.setField(
                app, "toolCallbackProvider", (ToolCallbackProvider) () -> new ToolCallback[0]);
        var vectors = mock(VectorStore.class);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        ReflectionTestUtils.setField(app, "pgVectorVectorStore", vectors);
        String output =
                String.join(
                        "",
                        app.doChatByStream(
                                        "保存沟通建议",
                                        "7_sdk",
                                        Map.of(
                                                GeneratedFileService.USER_ID,
                                                7L,
                                                GeneratedFileService.RUN_ID,
                                                "love-sdk-run"))
                                .collectList()
                                .block(Duration.ofSeconds(5)));
        assertTrue(output.contains("/api/files/file/love-sdk-run?name=advice.txt"), output);
        assertEquals(
                "先沟通",
                Files.readString(files.requireDownload(7L, "file", "love-sdk-run", "advice.txt")));
        verify(api, times(2)).chatCompletionStream(any(ChatCompletionRequest.class), any());
        verify(memory, timeout(1000)).close("love", "7_sdk");
    }

    /** 同步入口也由真实 DashScope 适配器执行受保护文件工具，再用工具结果完成最终回答。 */
    @Test
    void actualDashScopeSynchronousLoopAlsoSupportsAuthenticatedFileTools() throws Exception {
        var properties = new StorageProperties();
        properties.setBaseDir(directory.toString());
        var files =
                new GeneratedFileServiceImpl(
                        properties, "/api", directory.resolve("memory").toString());
        var api = mock(DashScopeApi.class);
        var function =
                Map.of(
                        "name",
                        "writeFile",
                        "arguments",
                        "{\"fileName\":\"sync.txt\",\"content\":\"同步建议\"}");
        ChatCompletionChunk tool =
                chunk(
                        "tool_calls",
                        "",
                        List.of(
                                Map.of(
                                        "id",
                                        "sync-call",
                                        "type",
                                        "function",
                                        "function",
                                        function)));
        ChatCompletionChunk answer = chunk("stop", "同步文件已生成", List.of());
        when(api.chatCompletionEntity(any(ChatCompletionRequest.class), any()))
                .thenReturn(
                        ResponseEntity.ok(
                                new ChatCompletion(tool.requestId(), tool.output(), tool.usage())))
                .thenReturn(
                        ResponseEntity.ok(
                                new ChatCompletion(
                                        answer.requestId(), answer.output(), answer.usage())));
        var model =
                new DashScopeChatModel(
                        api,
                        DashScopeChatOptions.builder().build(),
                        ToolCallingManager.builder().build(),
                        RetryTemplate.defaultInstance(),
                        ObservationRegistry.NOOP);
        var memory = mock(ContextMemoryService.class);
        when(memory.open(eq("love"), anyString())).thenAnswer(call -> new ArrayList<Message>());
        when(memory.prepare(eq("love"), anyString(), anyList()))
                .thenAnswer(call -> call.getArgument(2));
        var app = new LoveApp(model, memory);
        ReflectionTestUtils.setField(
                app,
                "allTools",
                ToolCallbacks.from(new FileOperationTool(directory.toString(), files)));
        assertEquals(
                "同步文件已生成",
                app.doChatWithTools(
                        "保存建议",
                        "7_sync",
                        Map.of(
                                GeneratedFileService.USER_ID,
                                7L,
                                GeneratedFileService.RUN_ID,
                                "love-sync-run")));
        assertEquals(
                "同步建议",
                Files.readString(files.requireDownload(7L, "file", "love-sync-run", "sync.txt")));
        verify(api, times(2)).chatCompletionEntity(any(ChatCompletionRequest.class), any());
        verify(memory).close("love", "7_sync");
    }

    /** 按供应商返回结构创建响应块，只替换网络返回值，保留 SDK 解析和工具执行逻辑。 */
    private ChatCompletionChunk chunk(String finish, String content, List<?> calls)
            throws Exception {
        var message = Map.of("role", "assistant", "content", content, "tool_calls", calls);
        var body =
                Map.of(
                        "request_id",
                        "sdk-test",
                        "output",
                        Map.of(
                                "choices",
                                List.of(Map.of("finish_reason", finish, "message", message))),
                        "usage",
                        Map.of("input_tokens", 1, "output_tokens", 1, "total_tokens", 2));
        return json.readValue(json.writeValueAsBytes(body), ChatCompletionChunk.class);
    }
}

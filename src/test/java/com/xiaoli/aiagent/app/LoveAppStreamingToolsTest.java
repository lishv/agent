package com.xiaoli.aiagent.app;

import com.xiaoli.aiagent.service.ContextMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 运行真实 ChatClient 和 Advisor，验证模型收到且可调用本地与 MCP 回调；不连接外部服务。 */
class LoveAppStreamingToolsTest {
    static class LocalTools {
        @Tool(description = "读取本地恋爱建议")
        public String localAdvice() { return "先沟通彼此的需求"; }
    }
    static class McpTools {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(description = "MCP 搜索约会图片")
        public String searchImages() { calls.incrementAndGet(); return "找到约会图片"; }
    }

    @Test void streamingRequestIncludesAndCanCallBothLocalAndMcpTools() {
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        var memory = mock(ContextMemoryService.class);
        when(memory.open(eq("love"), anyString())).thenAnswer(call -> new ArrayList<Message>());
        when(memory.prepare(eq("love"), anyString(), anyList())).thenAnswer(call -> call.getArgument(2));
        var app = new LoveApp(model, memory);
        var local = ToolCallbacks.from(new LocalTools());
        var remote = new McpTools();
        var provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(ToolCallbacks.from(remote));
        ReflectionTestUtils.setField(app, "allTools", local);
        ReflectionTestUtils.setField(app, "toolCallbackProvider", provider);
        var vectors = mock(VectorStore.class);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        ReflectionTestUtils.setField(app, "pgVectorVectorStore", vectors);
        var captured = new AtomicReference<List<ToolCallback>>();
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            var tools = ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks();
            captured.set(tools);
            assertTrue(tools.stream().filter(tool -> tool.getToolDefinition().name().equals("localAdvice"))
                    .findFirst().orElseThrow().call("{}").contains("先沟通彼此的需求"));
            String answer = tools.stream().filter(tool -> tool.getToolDefinition().name().equals("searchImages"))
                    .findFirst().orElseThrow().call("{}");
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
        });
        assertTrue(app.doChatByStream("搜索约会图片", "love-mcp-room").collectList().block().getFirst().contains("找到约会图片"));
        assertEquals(List.of("localAdvice", "searchImages"), captured.get().stream().map(tool -> tool.getToolDefinition().name()).toList());
        assertEquals(1, remote.calls.get());
        verify(memory).registerProfile(eq("love"), eq("love-mcp-room"), anyString(), argThat(tools -> tools.length == 2), eq(""));
        verify(memory, timeout(1000)).close("love", "love-mcp-room");
        verify(provider).getToolCallbacks();
    }
}

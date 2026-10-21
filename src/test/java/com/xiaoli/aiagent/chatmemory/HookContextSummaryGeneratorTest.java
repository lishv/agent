package com.xiaoli.aiagent.chatmemory;
import com.xiaoli.aiagent.exception.BusinessException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 运行真实 SummarizationHook 和 TokenTextSplitter，只有模型接口使用模拟实现。 */
class HookContextSummaryGeneratorTest {
    private final ContextTokenEstimator tokens = new ContextTokenEstimator();
    private final HookContextSummaryGenerator generator = new HookContextSummaryGenerator(tokens);

    private ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test void usesTheFrameworkDefaultSummaryPrompt() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(answer("保留用户目标与约束。"));
        assertEquals("保留用户目标与约束。", generator.summarize(model, "两人预算300元，不吃辣。", 4096, 128));
        verify(model).call(argThat((Prompt prompt) -> prompt.getUserMessage().getText().contains("<primary_objective>")
                && prompt.getUserMessage().getText().contains("Context Extraction Assistant")
                && prompt.getUserMessage().getText().contains("两人预算300元，不吃辣。")));
    }

    @Test void longHistoryUsesFrameworkChunksAndMergesPriorSummariesWithinBudget() {
        var model = mock(ChatModel.class);
        var calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> answer("分块摘要" + calls.incrementAndGet()));
        String history = "起始目标唯一标识。" + "文件证据与待办事项。".repeat(1800) + "历史尾部唯一标识。";
        String summary = generator.summarize(model, history, 4096, 128);
        assertTrue(calls.get() > 1);
        assertEquals("分块摘要" + calls.get(), summary);
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(calls.get())).call(captured.capture());
        var requests = captured.getAllValues();
        assertTrue(requests.getFirst().getUserMessage().getText().contains("起始目标唯一标识"));
        assertTrue(requests.getLast().getUserMessage().getText().contains("历史尾部唯一标识"));
        assertTrue(requests.getLast().getUserMessage().getText().contains("分块摘要" + (calls.get() - 1)));
        assertTrue(requests.stream().allMatch(prompt -> tokens.computeMessagesTokens(prompt.getInstructions()) <= 4096));
    }

    @Test void propagatesModelFailureInsteadOfAcceptingTheHooksErrorString() {
        var model = mock(ChatModel.class);
        var failure = new IllegalStateException("模型暂时不可用");
        when(model.call(any(Prompt.class))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> generator.summarize(model, "需要保留的真实历史", 4096, 128)));
    }

    @Test void rejectsBlankTruncatedToolCallingAndOversizedSummaries() {
        var model = mock(ChatModel.class);
        var truncated = new ChatResponse(List.of(new Generation(new AssistantMessage("未完成的摘要"),
                ChatGenerationMetadata.builder().finishReason("length").build())));
        var toolCall = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("调用工具")
                .toolCalls(List.of(new AssistantMessage.ToolCall("forbidden", "function", "readFile", "{}"))).build())));
        for (ChatResponse invalid : List.of(answer(" "), truncated, toolCall, answer("超长摘要。".repeat(100)))) {
            when(model.call(any(Prompt.class))).thenReturn(invalid);
            assertThrows(BusinessException.class, () -> generator.summarize(model, "真实历史", 4096, 128));
        }
    }
}

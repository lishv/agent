package com.xiaoli.aiagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import com.xiaoli.aiagent.agent.model.AgentState;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 未注册工具应产生可读的工具响应，不能丢失调用记录或使其他工具重复执行。 */
class MissingToolResultTest {
    @Test
    void missingToolReturnsAnExplanationAndTheModelCanContinue() {
        var model = modelReturning(List.of(call("missing-1", "unknownTool")));
        var agent = new MyManus(new ToolCallback[0], model, () -> new ToolCallback[0]);

        assertTrue(agent.think());
        String result = assertDoesNotThrow(agent::act);

        assertTrue(result.contains("未找到工具"), result);
        var message = (ToolResponseMessage) agent.getMessageList().getLast();
        var response = message.getResponses().getFirst();
        assertEquals("missing-1", response.id());
        assertEquals("unknownTool", response.name());
        assertTrue(response.responseData().contains("unknownTool"));
        assertFalse(agent.think());
        assertEquals("已根据工具返回的信息继续处理", agent.getMessageList().getLast().getText());
    }

    @Test
    void knownToolIsExecutedOnceWhenTheSameBatchContainsAnUnknownTool() {
        var fixture = new KnownTools();
        var model = modelReturning(List.of(call("known-1", "knownTool"), call("missing-1", "unknownTool")));
        var agent = new MyManus(ToolCallbacks.from(fixture), model, () -> new ToolCallback[0]);

        assertTrue(agent.think());
        String result = assertDoesNotThrow(agent::act);

        assertEquals(1, fixture.calls.get());
        assertTrue(result.contains("已完成"));
        assertTrue(result.contains("未找到工具"));
        var message = (ToolResponseMessage) agent.getMessageList().getLast();
        assertEquals(2, message.getResponses().size());
    }

    @Test
    void missingTerminationToolDoesNotFinishTheTask() {
        var model = modelReturning(List.of(call("missing-stop", "doTerminate")));
        var agent = new MyManus(new ToolCallback[0], model, () -> new ToolCallback[0]);
        agent.setState(AgentState.RUNNING);

        assertTrue(agent.think());
        assertTrue(assertDoesNotThrow(agent::act).contains("未找到工具"));
        assertEquals(AgentState.RUNNING, agent.getState());
        verify(model, times(1)).call(any(Prompt.class));
    }

    /** 返回一次工具决策和一次正常答复，使用真实工具执行管理器。 */
    private ChatModel modelReturning(List<AssistantMessage.ToolCall> calls) {
        var model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build()))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("已根据工具返回的信息继续处理")))));
        return model;
    }

    /** 构造具有独立 ID 的工具调用，用于核对响应与原调用之间的对应关系。 */
    private AssistantMessage.ToolCall call(String id, String name) {
        return new AssistantMessage.ToolCall(id, "function", name, "{}");
    }

    static class KnownTools {
        final AtomicInteger calls = new AtomicInteger();

        @Tool(description = "用于验证正常工具只执行一次")
        public String knownTool() {
            calls.incrementAndGet();
            return "已完成";
        }
    }
}

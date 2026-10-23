package com.xiaoli.aiagent.agent;

import com.xiaoli.aiagent.advisor.MyLoggerAdvisor;
import com.xiaoli.aiagent.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 空响应应明确报错，日志不能抛空指针；只有工具调用的正常响应仍可继续。 */
class EmptyModelResponseTest {
    @Test
    void loggerPreservesEmptyResponseWithoutThrowing() {
        var request = ChatClientRequest.builder().prompt(new Prompt("测试")).build();
        var response = ChatClientResponse.builder().chatResponse(new ChatResponse(List.of())).build();
        var chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(request)).thenReturn(response);

        assertSame(response, new MyLoggerAdvisor().adviseCall(request, chain));
    }

    @Test
    void emptyModelResultReportsClearBusinessError() {
        var agent = agentReturning(new ChatResponse(List.of()));

        var error = assertThrows(BusinessException.class, agent::think);

        assertEquals("模型返回空结果，请稍后重试", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void toolCallWithoutAnswerTextRemainsValid() {
        var message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "doTerminate", "{}")))
                .build();

        assertTrue(agentReturning(new ChatResponse(List.of(new Generation(message)))).think());
    }

    @Test
    void ordinaryAnswerRemainsValid() {
        var agent = agentReturning(new ChatResponse(List.of(new Generation(new AssistantMessage("正常回答")))));

        assertFalse(agent.think());
        assertEquals("正常回答", agent.getMessageList().getLast().getText());
    }

    /** 使用模型替身执行真实 ChatClient 和日志 Advisor，不连接外部模型。 */
    private MyManus agentReturning(ChatResponse response) {
        var model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(response);
        return new MyManus(new ToolCallback[0], model, () -> new ToolCallback[0]);
    }
}

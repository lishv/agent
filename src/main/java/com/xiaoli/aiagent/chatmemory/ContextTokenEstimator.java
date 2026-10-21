package com.xiaoli.aiagent.chatmemory;

import org.springframework.ai.chat.messages.*;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.stereotype.Component;
import java.util.List;

/** 使用本地 tokenizer 估算；不同模型的编码、协议包装可能不同，前端始终标注估算。 */
@Component
public class ContextTokenEstimator {
    private final JTokkitTokenCountEstimator tokenizer = new JTokkitTokenCountEstimator();
    //运用 tokenizer 估算文本占用的 Token 数
    public int text(String value) { return value == null || value.isEmpty() ? 0 : tokenizer.estimate(value); }

    /**
     * 估算消息列表占用的 Token 数。
     * @param messages
     * @return
     */
    public int computeMessagesTokens(List<Message> messages) {
        long count = 3;
        for (Message message : messages) {
            //每条消息的基础包装开销 + 文本 token
            count += 6L + text(message.getText());
            //AssistantMessage 里的工具调用额外开销
            if (message instanceof AssistantMessage assistant) {
                //AssistantMessage 里的工具调用额外开销,12L 是工具调用的包装开销,每个工具调用的名称、参数、ID 都占用 Token 数
                for (var tool : assistant.getToolCalls()) count += 12L + text(tool.name()) + text(tool.arguments()) + text(tool.id());
            }
            //ToolResponseMessage 里的工具结果额外开销,12L 是工具结果的包装开销,每个工具结果的名称、数据、ID 都占用 Token 数
            if (message instanceof ToolResponseMessage response) {
                for (var tool : response.getResponses()) count += 12L + text(tool.name()) + text(tool.responseData()) + text(tool.id());
            }
            //MediaContent每条 MediaContent 可携带 N 个媒体文件（图片/音频）， 里的多模态额外开销
            //目前每个媒体算 1024 个 Token，
            if (message instanceof MediaContent media) count += media.getMedia().size() * 1024L;
        }
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    /**
     * 估算系统提示、工具定义及控制提示占用占 Token 数
     * @param systemPrompt
     * @param tools
     * @return
     */
    public int computeOverheadTokens(String systemPrompt, ToolCallback[] tools) {
        long count = 12L + text(systemPrompt);
        for (ToolCallback callback : tools) {
            var definition = callback.getToolDefinition();
            count += 16L + text(definition.name()) + text(definition.description()) + text(definition.inputSchema());
        }
        return (int) Math.min(count, Integer.MAX_VALUE);
        /*
        getChatClient()
            .prompt(prompt)
            .system(getSystemPrompt())
            .call()
            .chatResponse();
         */
    }

    /**
     * 生成消息列表的文本转录。
     * @param messages
     * @return
     */
    public String transcript(List<Message> messages) {
        StringBuilder result = new StringBuilder();
        for (Message message : messages) {
            result.append('\n').append(message.getMessageType()).append(": ").append(message.getText() == null ? "" : message.getText());
            if (message instanceof AssistantMessage assistant) for (var tool : assistant.getToolCalls())
                result.append("\n工具调用 ").append(tool.name()).append(" [").append(tool.id()).append("] ").append(tool.arguments());
            if (message instanceof ToolResponseMessage response) for (var tool : response.getResponses())
                result.append("\n工具结果 ").append(tool.name()).append(" [").append(tool.id()).append("] ").append(tool.responseData());
        }
        return result.toString();
        /*
        List<Message> messages = List.of(
                // 第 1 轮
                new UserMessage("帮我搜北京天气"),
                AssistantMessage.builder()
                    .text("好的，我先搜天气")
                    .toolCalls(List.of(ToolCall.builder()
                        .name("search").arguments("{\"query\":\"北京天气\"}").id("call_001").build()))
                    .build(),
                new ToolResponseMessage(List.of(ToolResponse.builder()
                    .name("search").responseData("{\"temp\":25,\"weather\":\"晴\"}").id("call_001").build())),

                // 第 2 轮
                new UserMessage("顺便帮我存笔记"),
                AssistantMessage.builder()
                    .text("好的，我存一条笔记")
                    .toolCalls(List.of(ToolCall.builder()
                        .name("note").arguments("{\"content\":\"北京晴25度\"}").id("call_002").build()))
                    .build(),
                new ToolResponseMessage(List.of(ToolResponse.builder()
                    .name("note").responseData("{\"status\":\"saved\"}").id("call_002").build()))
);
         */
        /*
        USER: 帮我搜北京天气
        ASSISTANT: 好的，我先搜天气
        工具调用 search [call_001] {"query":"北京天气"}
        TOOL:
        工具结果 search [call_001] {"temp":25,"weather":"晴"}

        USER: 顺便帮我存笔记
        ASSISTANT: 好的，我存一条笔记
        工具调用 note [call_002] {"content":"北京晴25度"}
        TOOL:
        工具结果 note [call_002] {"status":"saved"}

         */
    }
}

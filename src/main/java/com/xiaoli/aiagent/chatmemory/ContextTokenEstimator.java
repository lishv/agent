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
    public int text(String value) { return value == null || value.isEmpty() ? 0 : tokenizer.estimate(value); }
    public int messages(List<Message> messages) {
        long count = 3;
        for (Message message : messages) {
            count += 6L + text(message.getText());
            if (message instanceof AssistantMessage assistant) {
                for (var tool : assistant.getToolCalls()) count += 12L + text(tool.name()) + text(tool.arguments()) + text(tool.id());
            }
            if (message instanceof ToolResponseMessage response) {
                for (var tool : response.getResponses()) count += 12L + text(tool.name()) + text(tool.responseData()) + text(tool.id());
            }
            if (message instanceof MediaContent media) count += media.getMedia().size() * 1024L;
        }
        return (int) Math.min(count, Integer.MAX_VALUE);
    }
    public int overhead(String systemPrompt, ToolCallback[] tools) {
        long count = 12L + text(systemPrompt);
        for (ToolCallback callback : tools) {
            var definition = callback.getToolDefinition();
            count += 16L + text(definition.name()) + text(definition.description()) + text(definition.inputSchema());
        }
        return (int) Math.min(count, Integer.MAX_VALUE);
    }
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
    }
}

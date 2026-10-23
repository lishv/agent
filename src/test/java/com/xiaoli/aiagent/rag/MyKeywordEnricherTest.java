package com.xiaoli.aiagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 多个聊天模型并存时，关键词增强使用 DashScope，避免等待慢速网关。 */
class MyKeywordEnricherTest {
    @Test
    void extractsKeywordsWithDashScopeAndPreservesRetrievalMetadata() {
        ChatModel dashscope = mock(ChatModel.class);
        ChatModel openai = mock(ChatModel.class);
        ChatModel ollama = mock(ChatModel.class);
        when(dashscope.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("沟通,关系,倾听,表达,约会")))));
        when(openai.call(any(Prompt.class))).thenThrow(new ResourceAccessException("Gateway timed out"));
        var document = new Document("通过倾听和表达改善沟通。", Map.of("status", "恋爱", "filename", "恋爱篇.md"));

        new ApplicationContextRunner().withUserConfiguration(MyKeywordEnricher.class)
                .withBean("dashScopeChatModel", ChatModel.class, () -> dashscope)
                .withBean("openAiChatModel", ChatModel.class, () -> openai)
                .withBean("ollamaChatModel", ChatModel.class, () -> ollama)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var enriched = context.getBean(MyKeywordEnricher.class).enrichDocuments(List.of(document));
                    assertEquals(1, enriched.size());
                    assertEquals(document.getText(), enriched.getFirst().getText());
                    assertEquals("恋爱", enriched.getFirst().getMetadata().get("status"));
                    assertEquals("恋爱篇.md", enriched.getFirst().getMetadata().get("filename"));
                    assertEquals("沟通,关系,倾听,表达,约会", enriched.getFirst().getMetadata().get("excerpt_keywords"));
                    verify(dashscope).call(any(Prompt.class));
                    verifyNoInteractions(openai, ollama);
                });
    }
}

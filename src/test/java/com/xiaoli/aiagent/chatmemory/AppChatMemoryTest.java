package com.xiaoli.aiagent.chatmemory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppChatMemoryTest {
    @TempDir Path directory;

    @Test
    void identicalChatIdsAreIsolatedBetweenApplications() {
        var config = new AppChatMemoryConfig(directory.toString());
        var love = config.loveChatMemoryRepository();
        var manus = config.manusChatMemoryRepository();
        love.saveAll("same-chat", List.of(new UserMessage("恋爱问题")));
        manus.saveAll("same-chat", List.of(new UserMessage("Java 学习任务")));
        assertEquals("恋爱问题", love.findByConversationId("same-chat").getFirst().getText());
        assertEquals("Java 学习任务", manus.findByConversationId("same-chat").getFirst().getText());
        assertTrue(Files.exists(directory.resolve("love/same-chat.kryo")));
        assertTrue(Files.exists(directory.resolve("manus/same-chat.kryo")));
    }

    @Test
    void ignoresLegacyMemoryAndLeavesFilesForManualCleanup() {
        var legacy = new FileBasedChatMemoryRepository(directory.toString());
        legacy.saveAll("old-chat", List.of(new UserMessage("旧的恋爱对话")));
        var config = new AppChatMemoryConfig(directory.toString());
        var love = config.loveChatMemoryRepository();
        assertTrue(love.findByConversationId("old-chat").isEmpty());
        assertFalse(Files.exists(directory.resolve("love/old-chat.kryo")));
        love.saveAll("old-chat", List.of(new UserMessage("更新后的恋爱对话")));
        love = config.loveChatMemoryRepository();
        assertEquals("更新后的恋爱对话", love.findByConversationId("old-chat").getFirst().getText());
        assertTrue(Files.exists(directory.resolve("old-chat.kryo")));
        assertTrue(config.manusChatMemoryRepository().findByConversationId("old-chat").isEmpty());
    }

    @Test
    void restoresFullToolCallAndResponseMessagesFromDisk() {
        var repository = new FileBasedChatMemoryRepository(directory.resolve("manus").toString());
        var assistant = AssistantMessage.builder().content("正在读取文件")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "readFile", "{\"path\":\"notes.txt\"}"))).build();
        var response = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call-1", "readFile", "文件内容：学习 Java"))).build();
        repository.saveAll("tool-chat", List.of(new UserMessage("读取文件"), assistant, response, new AssistantMessage("已完成")));
        var restored = new FileBasedChatMemoryRepository(directory.resolve("manus").toString()).findByConversationId("tool-chat");
        assertEquals(4, restored.size());
        assertEquals("call-1", ((AssistantMessage) restored.get(1)).getToolCalls().getFirst().id());
        assertEquals("文件内容：学习 Java", ((ToolResponseMessage) restored.get(2)).getResponses().getFirst().responseData());
    }

    @Test
    void springAiAutoConfigurationCanResolveTheTwoNamedRepositories() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AppChatMemoryConfig.class, () -> new AppChatMemoryConfig(directory.toString()));
            context.register(ChatMemoryAutoConfiguration.class);
            context.refresh();
            assertNotNull(context.getBean(ChatMemory.class));
            assertSame(context.getBean("loveChatMemoryRepository"), context.getBean(ChatMemoryRepository.class));
            assertNotSame(context.getBean("loveChatMemoryRepository"), context.getBean("manusChatMemoryRepository"));
        }
    }
}

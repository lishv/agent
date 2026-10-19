package com.xiaoli.aiagent.interaction;

import com.xiaoli.aiagent.service.ManusRunService;
import com.xiaoli.aiagent.service.impl.*;
import com.xiaoli.aiagent.agent.MyManus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ManusInteractionWiringTest {
    @Test
    void springCanWireBothTheDefaultAgentAndTheInteractiveRunService() {
        try (var context = new AnnotationConfigApplicationContext(WiringConfig.class)) {
            assertNotNull(context.getBean(ManusRunService.class));
            assertEquals(0, context.getBean(MyManus.class).getAvailableTools().length);
            assertFalse(context.getBean(ManusRunService.class).cancel("not-running"));
        }
    }

    @Configuration
    @Import({MyManus.class, ManusRunServiceImpl.class})
    static class WiringConfig {
        @Bean(name = "allTools") ToolCallback[] tools() { return new ToolCallback[0]; }
        @Bean(name = "openAiChatModel") ChatModel model() { return mock(ChatModel.class); }
        @Bean ToolCallbackProvider mcpTools() { return () -> new ToolCallback[0]; }
        @Bean("manusChatMemoryRepository") ChatMemoryRepository memory() { return new InMemoryChatMemoryRepository(); }
        @Bean com.xiaoli.aiagent.service.ContextMemoryService contexts() { return mock(com.xiaoli.aiagent.service.ContextMemoryService.class); }
    }
}

package com.xiaoli.aiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class ContextMemoryPropertiesTest {
    @Test void yamlStyleOverridesKeepSeparateAppBudgetsAndDefaultPolicy() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(ContextMemoryProperties.class).withPropertyValues(
                        "chat-memory.compaction.apps.love.context-window-tokens=65536",
                        "chat-memory.compaction.apps.love.response-reserve-tokens=8192",
                        "chat-memory.compaction.apps.manus.context-window-tokens=32768",
                        "chat-memory.compaction.auto-enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var settings = context.getBean(ContextMemoryProperties.class);
                    assertEquals(57344, settings.budget("love").inputBudget());
                    assertEquals(28672, settings.budget("manus").inputBudget());
                    assertEquals(.7, settings.getThreshold()); assertEquals(4, settings.getRetainTurns());
                    assertFalse(settings.isAutoEnabled());
                });
    }
    @Test void invalidBudgetsFailBeforeServingModelRequests() {
        var settings = new ContextMemoryProperties();
        settings.getApps().get("love").setContextWindowTokens(4096);
        assertThrows(IllegalStateException.class, () -> settings.budget("love"));
    }
}

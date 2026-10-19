package com.xiaoli.imagesearchmcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.ToolCallbackProvider;
import static org.junit.jupiter.api.Assertions.*;

// 测试检查工具装配，不启动会占用 Surefire 标准输入的 stdio 传输。
@SpringBootTest(properties = "spring.ai.mcp.server.enabled=false")
class ImageSearchMcpServerApplicationTests {
    @Autowired private ToolCallbackProvider tools;

    @Test
    void contextLoads() {
        var callbacks = tools.getToolCallbacks();
        assertEquals(1, callbacks.length);
        assertEquals("searchImage", callbacks[0].getToolDefinition().name());
        assertTrue(callbacks[0].getToolDefinition().description().contains("Pixabay"));
        assertTrue(callbacks[0].call("{\"query\":\"\"}").contains("invalid_query"));
    }

}

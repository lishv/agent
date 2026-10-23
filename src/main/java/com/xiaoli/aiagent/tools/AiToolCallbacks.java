package com.xiaoli.aiagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.stream.Stream;

/** 两个助手共用同一图片搜索增强，保留其他本地与 MCP 回调的原始行为和顺序。 */
public final class AiToolCallbacks {
    /** 仅提供静态回调组合方法。 */
    private AiToolCallbacks() {}

    /** 只替换已连接的 searchImage 回调，向模型提供可降级、可指定数量和尺寸的工具。 */
    public static ToolCallback[] merge(ToolCallback[] local, ToolCallback[] remote) {
        return Stream.concat(
                        Arrays.stream(local),
                        Arrays.stream(remote).map(AiToolCallbacks::enhanceImageSearch))
                .toArray(ToolCallback[]::new);
    }

    /** 为图片 MCP 增加数量、尺寸和备用检索能力，其他回调保持原实例。 */
    private static ToolCallback enhanceImageSearch(ToolCallback tool) {
        return "searchImage".equals(tool.getToolDefinition().name())
                ? ToolCallbacks.from(new ImageSearchTool(tool))[0] : tool;
    }
}

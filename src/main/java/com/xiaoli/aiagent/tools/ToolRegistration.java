package com.xiaoli.aiagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 集中的工具注册类
 */
@Configuration
public class ToolRegistration {

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Bean
    public ToolCallback[] allTools() {
        FileOperationTool fileOperationTool = new FileOperationTool();
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
        WebSearchScrapingTool webSearchScrapingTool = new WebSearchScrapingTool();
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool();
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        TerminateTool terminateTool = new TerminateTool();
        AskHumanTool askHumanTool = new AskHumanTool(question ->
                """
                【示例回答，仅用于演示】
                两个人的总预算为 300 元。
                希望以公园散步、吃饭和喝咖啡为主。
                不吃辣，不安排需要长距离步行的活动。
                """
        );
        // 注册所有工具
        return ToolCallbacks.from(
                fileOperationTool,
                //webSearchTool,
                webSearchScrapingTool,
                webScrapingTool,
                resourceDownloadTool,
                terminalOperationTool,
                pdfGenerationTool,
                askHumanTool,
                terminateTool
        );
    }
}

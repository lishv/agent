package com.xiaoli.aiagent.tools;

import com.xiaoli.aiagent.constant.FileConstant;
import com.xiaoli.aiagent.service.GeneratedFileService;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 集中的工具注册类 */
@Configuration
public class ToolRegistration {

    @Value("${app.storage.base-dir:./tmp}")
    private String storageDirectory = FileConstant.FILE_SAVE_DIR;

    @Value("${pdf.fonts.regular:classpath:fonts/NotoSansSC-Regular.otf}")
    private String regularFont = PDFGenerationTool.DEFAULT_REGULAR_FONT;

    @Value("${pdf.fonts.bold:classpath:fonts/NotoSansSC-Bold.otf}")
    private String boldFont = PDFGenerationTool.DEFAULT_BOLD_FONT;

    /** 按部署目录与字体配置创建本地工具，再统一转换为模型工具回调。 */
    @Bean
    public ToolCallback[] allTools(GeneratedFileService generatedFiles) {
        return ToolCallbacks.from(
                new FileOperationTool(storageDirectory, generatedFiles),
                new WebSearchScrapingTool(),
                new WebScrapingTool(),
                new ResourceDownloadTool(storageDirectory, generatedFiles),
                new TerminalOperationTool(),
                new PDFGenerationTool(storageDirectory, regularFont, boldFont, generatedFiles),
                new TerminateTool());
    }

    /** 独立测试工具注册时不装配 Spring，保持原有本地工具构造方式。 */
    public ToolCallback[] allTools() {
        return allTools(null);
    }
}

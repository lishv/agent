package com.xiaoli.aiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 恋爱大师应用文档加载器
 */
@Component
@Slf4j
public class LoveAppDocumentLoader {
    //文档加载器配置
    private final ResourcePatternResolver resourcePatternResolver;

    public LoveAppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        // 初始化文档加载器配置
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     * 加载多篇 Markdown 文档
     * @return  加载后的文档列表
     */
    public List<Document> loadMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            // 加载所有 Markdown 文档
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            // 遍历所有 Markdown 文档
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                // 提取文档倒数第 3 和第 2 个字作为文档状态
                String status = filename.substring(filename.length() - 6, filename.length() - 4);
                // 配置 Markdown 文档读取器
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true) //以--- 或 *** 水平分割线时拆分为新文档
                        .withIncludeCodeBlock(false)// 是否将代码块合并到文档中（false=代码块单独拆分为Document）
                        .withIncludeBlockquote(false)// 是否将引用块合并到文档中（false=引用块单独拆分为Document）
                        .withAdditionalMetadata("filename", filename) // 为每个Document附加源文件名
                        .withAdditionalMetadata("status", status) // 为每个Document附加文档状态标识
                        .build();
                MarkdownDocumentReader markdownDocumentReader = new MarkdownDocumentReader(resource, config);
                allDocuments.addAll(markdownDocumentReader.get());
            }
        } catch (IOException e) {
           log.error("Markdown 文档加载失败", e);
        }
        return allDocuments;
    }
}

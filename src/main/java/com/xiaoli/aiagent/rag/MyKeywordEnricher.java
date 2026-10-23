package com.xiaoli.aiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;

import java.util.List;

/** 使用 DashScope 聊天模型为知识库文档补充关键词。 */
@Component
public class MyKeywordEnricher {

    @Resource
    private ChatModel dashScopeChatModel;

    /** 为每个文档提取 5 个关键词，保留原有文本及检索元数据。 */
    public List<Document> enrichDocuments(List<Document> documents) {
        return new KeywordMetadataEnricher(dashScopeChatModel, 5).apply(documents);
    }
}

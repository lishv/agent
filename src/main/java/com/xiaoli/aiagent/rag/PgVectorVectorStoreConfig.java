package com.xiaoli.aiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

// 为方便开发调试和部署，临时注释，如果需要使用 PgVector 存储知识库，取消注释即可
@Configuration
public class PgVectorVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;
    @Resource
    private MyTokenTextSplitter myTokenTextSplitter;
    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    @Bean
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate, @Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel) {
        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, ollamaEmbeddingModel)
                .dimensions(1024)                    // 向量维度，必须与 Embedding 模型输出维度一致（此处 Ollama 模型为 1024 维）
                .distanceType(COSINE_DISTANCE)       // 距离度量方式：余弦距离，值越小越相似（默认就是这个）
                .indexType(HNSW)                     // 向量索引类型：HNSW 近似最近邻搜索，速度快（默认就是这个）
                .initializeSchema(true)              // 是否自动建表：启动时自动在数据库创建向量表和索引
                .schemaName("public")                // PostgreSQL 的 Schema 名称（默认 public）
                .vectorTableName("vector_store")     // 存储向量的数据表名（默认 vector_store）
                .maxDocumentBatchSize(10000)         // 批量写入时单次最大处理的文档数（默认 10000）
                .build();
        // 加载文档,转化为向量,并添加到向量存储
        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
        // 自主切分文档(不推荐,可以使用阿里云的分词服务)
        //List<Document> splitDocuments = myTokenTextSplitter.splitCustomized(documents);
        // 自定义关键词增强文档
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(documents);
        vectorStore.add(enrichedDocuments);
        return vectorStore;
    }
}

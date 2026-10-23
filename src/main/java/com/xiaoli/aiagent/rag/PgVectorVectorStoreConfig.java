package com.xiaoli.aiagent.rag;

import cn.hutool.core.collection.ListUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/** 创建知识库连接；只有显式开启初始化时，才在启动时导入文档。 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.vector-store.enabled", havingValue = "true")
public class PgVectorVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;
    @Resource
    private MyTokenTextSplitter myTokenTextSplitter;
    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    @Value("${app.vector-store.initialize-data:false}")
    private boolean initializeData;

    /** 创建向量库 Bean，由 Spring 在其初始化阶段自动建表和创建索引。 */
    @Bean
    public VectorStore pgVectorVectorStore(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbcTemplate, @Qualifier("dashscopeEmbeddingModel") EmbeddingModel dashscopeEmbeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1024)                    // 与 DashScope 嵌入模型配置的输出维度一致
                .distanceType(COSINE_DISTANCE)       // 距离度量方式：余弦距离，值越小越相似（默认就是这个）
                .indexType(HNSW)                     // 向量索引类型：HNSW 近似最近邻搜索，速度快（默认就是这个）
                .initializeSchema(true)              // 是否自动建表：启动时自动在数据库创建向量表和索引
                .schemaName("public")                // PostgreSQL 的 Schema 名称（默认 public）
                .vectorTableName("vector_store")     // 存储向量的数据表名（默认 vector_store）
                .maxDocumentBatchSize(10000)         // 数据库写入批量大小，不控制嵌入接口的文本数量
                .build();
    }

    /** 所有单例初始化完成后再导入文档，确保向量表和索引已创建。 */
    @Bean
    public SmartInitializingSingleton vectorDataInitializer(@Qualifier("pgVectorVectorStore") VectorStore vectorStore) {
        return () -> {
            if (!initializeData) {
                log.info("已跳过启动时的向量数据导入，使用已有知识库");
                return;
            }
            List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
            log.info("已加载 {} 个知识库文档片段，开始生成关键词", documents.size());
            List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(documents);
            log.info("关键词生成完成，开始分批生成向量并写入知识库");
            // text-embedding-v4 每次最多接受 10 条文本；每批仍使用框架原有的 Token 分批逻辑。
            ListUtil.partition(enrichedDocuments, 10).forEach(vectorStore::add);
            log.info("启动时的向量数据导入完成，共 {} 个文档", enrichedDocuments.size());
        };
    }
}

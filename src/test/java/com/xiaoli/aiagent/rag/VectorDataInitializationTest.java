package com.xiaoli.aiagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用模拟模型和数据库验证启动开关，不加载 Ollama 模型或写入真实知识库。 */
class VectorDataInitializationTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmbeddingModel embedding = mock(EmbeddingModel.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private final LoveAppDocumentLoader loader = mock(LoveAppDocumentLoader.class);
    private final MyKeywordEnricher enricher = mock(MyKeywordEnricher.class);
    private final MyTokenTextSplitter splitter = mock(MyTokenTextSplitter.class);

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(PgVectorVectorStoreConfig.class)
                .withPropertyValues("app.vector-store.enabled=true")
                .withBean("vectorJdbcTemplate", JdbcTemplate.class, () -> jdbc)
                .withBean("ollamaEmbeddingModel", EmbeddingModel.class, () -> embedding)
                .withBean("ollamaChatModel", ChatModel.class, () -> chatModel)
                .withBean(LoveAppDocumentLoader.class, () -> loader)
                .withBean(MyKeywordEnricher.class, () -> enricher)
                .withBean(MyTokenTextSplitter.class, () -> splitter);
    }
    private void simulateUnavailableOllama() {
        when(loader.loadMarkdowns()).thenReturn(List.of(new Document("已有知识")));
        when(enricher.enrichDocuments(anyList())).thenThrow(new ResourceAccessException("Ollama request timed out"));
    }
    @Test void defaultStartupDoesNotImportDocumentsOrCallModels() {
        simulateUnavailableOllama();
        context().run(ctx -> {
            assertNull(ctx.getStartupFailure()); assertNotNull(ctx.getBean(VectorStore.class));
            verifyNoInteractions(loader, enricher, embedding, chatModel);
        });
    }
    @Test void explicitFalseKeepsVectorStoreAvailableWithoutReimportingData() {
        simulateUnavailableOllama();
        context().withPropertyValues("app.vector-store.initialize-data=false").run(ctx -> {
            assertNull(ctx.getStartupFailure()); assertTrue(ctx.containsBean("pgVectorVectorStore"));
            verifyNoInteractions(loader, enricher, embedding, chatModel);
        });
    }
    @Test void explicitTrueImportsDocumentsAndCreatesEmbeddings() {
        List<Document> documents = List.of(new Document("新知识"));
        when(loader.loadMarkdowns()).thenReturn(documents);
        when(enricher.enrichDocuments(documents)).thenReturn(documents);
        when(embedding.embed(anyList(), any(), any())).thenReturn(List.of(new float[1024]));
        context().withPropertyValues("app.vector-store.initialize-data=true").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            verify(loader).loadMarkdowns(); verify(enricher).enrichDocuments(documents);
            verify(embedding).embed(eq(documents), any(), any());
        });
    }
}

package com.xiaoli.aiagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
                .withBean("dashscopeEmbeddingModel", EmbeddingModel.class, () -> embedding)
                .withBean("dashScopeChatModel", ChatModel.class, () -> chatModel)
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

    @Test void emptyDatabaseCreatesTableBeforeLoadingDocumentsAndWritingEmbeddings() {
        var tableCreated = new AtomicBoolean();
        List<Document> documents = List.of(new Document("首次导入的知识"));
        doAnswer(invocation -> {
            if (invocation.<String>getArgument(0).contains("CREATE TABLE IF NOT EXISTS public.vector_store")) {
                tableCreated.set(true);
            }
            return null;
        }).when(jdbc).execute(anyString());
        when(jdbc.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenAnswer(invocation -> {
            if (!tableCreated.get()) {
                throw new BadSqlGrammarException("batchUpdate", invocation.getArgument(0),
                        new SQLException("relation \"public.vector_store\" does not exist", "42P01"));
            }
            return new int[]{1};
        });
        when(loader.loadMarkdowns()).thenReturn(documents);
        when(enricher.enrichDocuments(documents)).thenReturn(documents);
        when(embedding.embed(anyList(), any(), any())).thenReturn(List.of(new float[1024]));

        context().withPropertyValues("app.vector-store.initialize-data=true").run(ctx -> {
            assertNull(ctx.getStartupFailure(), "首次启动应先自动建表，再导入文档");
            var order = inOrder(jdbc, loader, enricher, embedding);
            order.verify(jdbc).execute(contains("CREATE TABLE IF NOT EXISTS public.vector_store"));
            order.verify(jdbc).execute(contains("CREATE INDEX IF NOT EXISTS"));
            order.verify(loader).loadMarkdowns();
            order.verify(enricher).enrichDocuments(documents);
            order.verify(embedding).embed(eq(documents), any(), any());
            order.verify(jdbc).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
            verify(jdbc, times(1)).execute(contains("CREATE TABLE IF NOT EXISTS public.vector_store"));
        });
    }

    @Test void schemaFailureStopsStartupBeforeCallingModels() {
        List<Document> documents = List.of(new Document("新知识"));
        when(loader.loadMarkdowns()).thenReturn(documents);
        when(enricher.enrichDocuments(documents)).thenReturn(documents);
        when(embedding.embed(anyList(), any(), any())).thenReturn(List.of(new float[1024]));
        doThrow(new BadSqlGrammarException("createTable", "CREATE TABLE public.vector_store",
                new SQLException("permission denied for schema public", "42501")))
                .when(jdbc).execute(contains("CREATE TABLE IF NOT EXISTS public.vector_store"));

        context().withPropertyValues("app.vector-store.initialize-data=true").run(ctx -> {
            assertNotNull(ctx.getStartupFailure());
            verifyNoInteractions(loader, enricher, embedding, chatModel);
        });
    }
}

package com.xiaoli.aiagent.rag;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/** 使用真实 DashScope 适配器验证每批最多 10 条；HTTP 和数据库均为本地模拟。 */
class PgVectorEmbeddingBatchTest {
    @Test
    void importsTwentyOneDocumentsThroughThreeValidEmbeddingRequests() throws Exception {
        var mapper = new ObjectMapper();
        var client = RestClient.builder();
        var server = MockRestServiceServer.bindTo(client).build();
        List<Integer> batchSizes = new ArrayList<>();
        List<String> receivedTexts = new ArrayList<>();
        server.expect(ExpectedCount.manyTimes(), requestTo("http://embedding.test/api/v1/services/embeddings/text-embedding/text-embedding"))
                .andRespond(request -> {
                    var body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    var texts = body.path("input").path("texts");
                    assertTrue(texts.isArray(), body.toString());
                    batchSizes.add(texts.size());
                    texts.forEach(text -> receivedTexts.add(text.asText()));
                    var embeddings = new ArrayList<Map<String, Object>>();
                    for (int i = 0; i < texts.size(); i++) {
                        embeddings.add(Map.of("text_index", i, "embedding", new float[1024]));
                    }
                    boolean oversized = texts.size() > 10;
                    var payload = oversized
                            ? Map.of("request_id", "batch-test", "code", "InvalidParameter", "message", "batch size should not be larger than 10")
                            : Map.of("request_id", "batch-test", "output", Map.of("embeddings", embeddings), "usage", Map.of("total_tokens", 21));
                    var response = new MockClientHttpResponse(mapper.writeValueAsBytes(payload), oversized ? HttpStatus.BAD_REQUEST : HttpStatus.OK);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return response;
                });
        var api = DashScopeApi.builder().apiKey("local-test-only").baseUrl("http://embedding.test")
                .restClientBuilder(client).build();
        var embedding = new DashScopeEmbeddingModel(api, MetadataMode.EMBED,
                DashScopeEmbeddingOptions.builder().model("text-embedding-v4").dimensions(1024).build());
        List<Document> documents = IntStream.range(0, 21).mapToObj(i -> new Document("文档片段 " + i)).toList();
        var loader = mock(LoveAppDocumentLoader.class);
        var enricher = mock(MyKeywordEnricher.class);
        var jdbc = mock(JdbcTemplate.class);
        when(loader.loadMarkdowns()).thenReturn(documents);
        when(enricher.enrichDocuments(documents)).thenReturn(documents);
        new ApplicationContextRunner().withUserConfiguration(PgVectorVectorStoreConfig.class)
                .withPropertyValues("app.vector-store.enabled=true", "app.vector-store.initialize-data=true")
                .withBean("vectorJdbcTemplate", JdbcTemplate.class, () -> jdbc)
                .withBean("dashscopeEmbeddingModel", EmbeddingModel.class, () -> embedding)
                .withBean("dashScopeChatModel", ChatModel.class, () -> mock(ChatModel.class))
                .withBean(LoveAppDocumentLoader.class, () -> loader)
                .withBean(MyKeywordEnricher.class, () -> enricher)
                .withBean(MyTokenTextSplitter.class, () -> mock(MyTokenTextSplitter.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(VectorStore.class));
                });

        assertEquals(List.of(10, 10, 1), batchSizes);
        assertEquals(documents.stream().map(Document::getText).toList(), receivedTexts);
        var writes = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbc, times(3)).batchUpdate(anyString(), writes.capture());
        assertEquals(List.of(10, 10, 1), writes.getAllValues().stream().map(BatchPreparedStatementSetter::getBatchSize).toList());
        verify(enricher).enrichDocuments(documents);
        server.verify();
    }
}

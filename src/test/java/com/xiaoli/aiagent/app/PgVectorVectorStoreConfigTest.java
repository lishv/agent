package com.xiaoli.aiagent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

@SpringBootTest
public class PgVectorVectorStoreConfigTest {

    @Resource(name = "pgVectorVectorStore")
    VectorStore pgVectorVectorStore;

    @Resource
    JdbcTemplate jdbcTemplate;

    @Test
    void test() {
        List<Document> documents = List.of(
                new Document("Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!!", Map.of("meta1", "meta1")),
                new Document("The World is Big and Salvation Lurks Around the Corner"),
                new Document("You walk forward facing the past and you turn back toward the future.", Map.of("meta2", "meta2")));

        Integer beforeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.vector_store",
                Integer.class
        );

        pgVectorVectorStore.add(documents);

        Integer afterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.vector_store",
                Integer.class
        );

        List<Document> results = pgVectorVectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("Spring AI rocks")
                        .topK(5)
                        .similarityThreshold(0.0)
                        .build()
        );

        System.out.println("写入前数据量: " + beforeCount);
        System.out.println("写入后数据量: " + afterCount);
        results.forEach(document -> System.out.println(
                "相似度: " + document.getScore() + "，内容: " + document.getText()
        ));

        Assertions.assertEquals(
                documents.size(),
                afterCount - beforeCount,
                "文档没有完整写入 PGVector"
        );
        Assertions.assertFalse(results.isEmpty(), "相似度查询返回了空列表");
        Assertions.assertTrue(
                results.stream().anyMatch(document -> document.getText().contains("Spring AI rocks")),
                "查询结果中没有找到 Spring AI 文档"
        );
    }

}
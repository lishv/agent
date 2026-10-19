package com.xiaoli.aiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiHttpClientConfigTest {
    @Test
    void bootBuilderUsesHttp11AndSendsLongUtf8BodyIntact() throws Exception {
        String message = "中文长文本🙂".repeat(3000);
        var received = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/completion", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
                    .withUserConfiguration(AiHttpClientConfig.class).run(context -> {
                        assertNull(context.getStartupFailure());
                        RestClient client = context.getBean(RestClient.Builder.class).build();
                        var factory = (JdkClientHttpRequestFactory) ReflectionTestUtils.getField(client, "clientRequestFactory");
                        var httpClient = (HttpClient) ReflectionTestUtils.getField(factory, "httpClient");
                        assertEquals(HttpClient.Version.HTTP_1_1, httpClient.version());
                        assertEquals("ok", client.post().uri("http://127.0.0.1:" + server.getAddress().getPort() + "/completion")
                                .contentType(MediaType.APPLICATION_JSON).body(Map.of("message", message))
                                .retrieve().body(String.class));
                        assertEquals(message, new ObjectMapper().readTree(received.get()).path("message").asText());
                    });
        } finally { server.stop(0); }
    }
}

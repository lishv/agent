package com.xiaoli.aiagent.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.modelcontextprotocol.client.McpClient;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** 从实际 application.yml 加载配置，使用真实 MCP HTTP 客户端连接本机测试服务。 */
class AmapMcpConfigurationTest {
    private final ObjectMapper json = new ObjectMapper();

    /** 验证地图连接确实启用、Key 到达正确端点，且模型回调可以执行地点搜索。 */
    @Test
    void applicationConfigurationLoadsMapToolsThroughPortableHttpTransport() throws Exception {
        var methods = new CopyOnWriteArrayList<String>();
        var queries = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> respond(exchange, methods, queries));
        server.start();
        try {
            var source =
                    new YamlPropertySourceLoader()
                            .load("application", new ClassPathResource("application.yml"))
                            .getFirst();
            assertNotNull(
                    source.getProperty(
                            "spring.ai.mcp.client.streamable-http.connections.amap.url"));
            assertNotNull(
                    source.getProperty(
                            "spring.ai.mcp.client.streamable-http.connections.amap.endpoint"));
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    StreamableHttpHttpClientTransportAutoConfiguration.class))
                    .withInitializer(
                            context ->
                                    context.getEnvironment().getPropertySources().addLast(source))
                    .withPropertyValues(
                            "AMAP_MAPS_API_KEY=test-key",
                            "spring.ai.mcp.client.streamable-http.connections.amap.url=http://127.0.0.1:"
                                    + server.getAddress().getPort())
                    .run(
                            context -> {
                                assertNull(context.getStartupFailure());
                                @SuppressWarnings("unchecked")
                                var transports =
                                        (List<NamedClientMcpTransport>)
                                                context.getBean(
                                                        "streamableHttpHttpClientTransports");
                                var transport =
                                        transports.stream()
                                                .filter(item -> item.name().equals("amap"))
                                                .findFirst()
                                                .orElseThrow(
                                                        () ->
                                                                new AssertionError(
                                                                        "application.yml 没有启用高德"
                                                                            + " HTTP 连接"));
                                var client =
                                        McpClient.sync(transport.transport())
                                                .requestTimeout(Duration.ofSeconds(5))
                                                .build();
                                try {
                                    client.initialize();
                                    var callbacks =
                                            new SyncMcpToolCallbackProvider(client)
                                                    .getToolCallbacks();
                                    var search =
                                            Arrays.stream(callbacks)
                                                    .filter(
                                                            tool ->
                                                                    tool.getToolDefinition()
                                                                            .name()
                                                                            .endsWith(
                                                                                    "maps_text_search"))
                                                    .findFirst()
                                                    .orElseThrow();
                                    String result =
                                            search.call(
                                                    "{\"keywords\":\"公园\",\"city\":\"合肥\",\"citylimit\":true}");
                                    assertTrue(result.contains("合肥测试公园"), result);
                                    assertTrue(
                                            methods.containsAll(
                                                    List.of(
                                                            "initialize",
                                                            "tools/list",
                                                            "tools/call")),
                                            methods.toString());
                                    assertFalse(queries.isEmpty());
                                    assertTrue(
                                            queries.stream().allMatch("key=test-key"::equals),
                                            queries.toString());
                                } finally {
                                    client.closeGracefully();
                                }
                            });
        } finally {
            server.stop(0);
        }
    }

    /** 模拟标准 MCP 协议响应，不启动 Node、不访问高德或其他外部服务。 */
    private void respond(HttpExchange exchange, List<String> methods, List<String> queries)
            throws IOException {
        try {
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            queries.add(exchange.getRequestURI().getRawQuery());
            JsonNode request = json.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            methods.add(method);
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            Object result =
                    switch (method) {
                        case "initialize" ->
                                Map.of(
                                        "protocolVersion",
                                        "2025-03-26",
                                        "capabilities",
                                        Map.of("tools", Map.of("listChanged", false)),
                                        "serverInfo",
                                        Map.of("name", "amap-test", "version", "1.0"));
                        case "tools/list" ->
                                Map.of(
                                        "tools",
                                        List.of(
                                                Map.of(
                                                        "name",
                                                        "maps_text_search",
                                                        "description",
                                                        "按城市搜索地点",
                                                        "inputSchema",
                                                        Map.of(
                                                                "type",
                                                                "object",
                                                                "properties",
                                                                Map.of(
                                                                        "city",
                                                                                Map.of(
                                                                                        "type",
                                                                                        "string"),
                                                                        "keywords",
                                                                                Map.of(
                                                                                        "type",
                                                                                        "string"),
                                                                        "citylimit",
                                                                                Map.of(
                                                                                        "type",
                                                                                        "boolean"))))));
                        case "tools/call" -> {
                            assertEquals(
                                    "maps_text_search",
                                    request.path("params").path("name").asText());
                            assertEquals(
                                    "合肥",
                                    request.path("params").path("arguments").path("city").asText());
                            yield Map.of(
                                    "content",
                                    List.of(Map.of("type", "text", "text", "合肥测试公园")),
                                    "isError",
                                    false);
                        }
                        default -> Map.of();
                    };
            byte[] body =
                    json.writeValueAsString(
                                    Map.of(
                                            "jsonrpc",
                                            "2.0",
                                            "id",
                                            request.get("id"),
                                            "result",
                                            result))
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }
}

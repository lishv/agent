package com.xiaoli.imagesearchmcpserver.tools;

import static org.junit.jupiter.api.Assertions.*;

import cn.hutool.json.JSONUtil;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 慢查询占用网络时，其他用户的图片搜索应独立完成，不排队等待整段工具方法。 */
class ImageSearchConcurrencyTest {
    @Test
    void slowUpstreamRequestDoesNotBlockAnotherUsersSearch() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers);
            server.createContext(
                    "/api/",
                    exchange -> {
                        if (exchange.getRequestURI().getQuery().contains("slow")) {
                            entered.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        byte[] body = "{\"hits\":[]}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body);
                        }
                    });
            server.start();
            var tool =
                    new ImageSearchTool(
                            "test-key",
                            System::currentTimeMillis,
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
            var slow = workers.submit(() -> tool.searchImage("slow"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try {
                var fast = workers.submit(() -> tool.searchImage("kitten"));
                assertEquals(
                        "no_results",
                        JSONUtil.parseObj(fast.get(1, TimeUnit.SECONDS)).getStr("status"));
                assertFalse(slow.isDone());
            } finally {
                release.countDown();
                slow.get(3, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}

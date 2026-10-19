package com.xiaoli.aiagent.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 验证相对输出路径与配置目录，下载测试仅访问本进程创建的本地 HTTP 服务。 */
class ToolStoragePortabilityTest {
    @TempDir Path directory;

    @Test
    void fileOperationsUseConfiguredRelativeDirectoryAndPreserveUtf8() throws Exception {
        Path relative = Path.of("").toAbsolutePath().relativize(directory);
        var tool = new FileOperationTool(relative.toString());
        String result = tool.writeFile("nested/部署.txt", "中文文件内容");
        assertTrue(result.startsWith("File written successfully to: "), result);
        assertFalse(
                Path.of(result.substring("File written successfully to: ".length())).isAbsolute());
        assertEquals("中文文件内容", tool.readFile("nested/部署.txt"));
        assertEquals("中文文件内容", Files.readString(directory.resolve("file/nested/部署.txt")));
    }

    @Test
    void downloadsUseConfiguredRelativeDirectory() throws Exception {
        byte[] body = "本地下载内容".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/file",
                exchange -> {
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            Path relative = Path.of("").toAbsolutePath().relativize(directory);
            var tool = new ResourceDownloadTool(relative.toString());
            String result =
                    tool.downloadResource(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/file",
                            "nested/下载.txt");
            assertTrue(result.startsWith("Resource downloaded successfully to: "), result);
            assertFalse(
                    Path.of(result.substring("Resource downloaded successfully to: ".length()))
                            .isAbsolute());
            assertArrayEquals(
                    body, Files.readAllBytes(directory.resolve("download/nested/下载.txt")));
            var properties = new com.xiaoli.aiagent.config.StorageProperties();
            properties.setBaseDir(relative.toString());
            var files =
                    new com.xiaoli.aiagent.service.impl.GeneratedFileServiceImpl(
                            properties, "/custom-api", relative.resolve("chat-memory").toString());
            var context =
                    new org.springframework.ai.chat.model.ToolContext(
                            java.util.Map.of(
                                    com.xiaoli.aiagent.service.GeneratedFileService.USER_ID,
                                    7L,
                                    com.xiaoli.aiagent.service.GeneratedFileService.RUN_ID,
                                    "resource-run"));
            String managed =
                    new ResourceDownloadTool(relative.toString(), files)
                            .downloadResource(
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/file",
                                    "下载.txt",
                                    context);
            assertTrue(managed.contains("/custom-api/files/download/resource-run?name="), managed);
            String firstLinePath =
                    managed.substring("Resource downloaded successfully to: ".length())
                            .split("\\R")[0];
            assertFalse(Path.of(firstLinePath).isAbsolute());
            assertArrayEquals(
                    body,
                    Files.readAllBytes(
                            files.requireDownload(7L, "download", "resource-run", "下载.txt")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void terminalSelectsTheHostInterpreterWithoutAWindowsOnlyExecutablePath() {
        assertEquals(
                java.util.List.of("cmd.exe", "/d", "/c", "echo test"),
                TerminalOperationTool.shellCommand("echo test", true));
        assertEquals(
                java.util.List.of("sh", "-c", "echo test"),
                TerminalOperationTool.shellCommand("echo test", false));
    }
}

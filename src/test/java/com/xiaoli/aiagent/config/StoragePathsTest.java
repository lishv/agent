package com.xiaoli.aiagent.config;

import static org.junit.jupiter.api.Assertions.*;

import com.xiaoli.aiagent.chatmemory.AppChatMemoryConfig;
import com.xiaoli.aiagent.chatmemory.FileBasedChatMemoryRepository;
import com.xiaoli.aiagent.service.GeneratedFileService;
import com.xiaoli.aiagent.service.impl.GeneratedFileServiceImpl;
import com.xiaoli.aiagent.tools.ToolRegistration;
import com.xiaoli.aiagent.util.FilePathUtils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** 使用真实文件与独立 Spring 上下文验证部署目录，不连接数据库、模型或外部网络。 */
class StoragePathsTest {
    @TempDir Path directory;

    @Test
    void applicationYamlDefaultsUseRelativeStorageAndPackagedFonts() throws Exception {
        var environment = new MockEnvironment();
        var sources =
                new YamlPropertySourceLoader()
                        .load("application", new ClassPathResource("application.yml"));
        sources.forEach(source -> environment.getPropertySources().addLast(source));
        assertEquals("./tmp", environment.getProperty("app.storage.base-dir"));
        assertEquals("./tmp/chat-memory", environment.getProperty("chat-memory.base-dir"));
        assertEquals("PT24H", environment.getProperty("app.storage.file-retention"));
        assertEquals("PT5M", environment.getProperty("app.storage.cleanup-interval"));
        assertEquals(
                "classpath:fonts/NotoSansSC-Regular.otf",
                environment.getProperty("pdf.fonts.regular"));
        assertEquals(
                "classpath:fonts/NotoSansSC-Bold.otf", environment.getProperty("pdf.fonts.bold"));
    }

    @Test
    void relativeStorageRootIsSharedByChatMemoryAndRegisteredTools() {
        Path relativeRoot = Path.of("").toAbsolutePath().relativize(directory);
        context(relativeRoot)
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            var memory =
                                    ctx.getBean(
                                            "loveChatMemoryRepository",
                                            FileBasedChatMemoryRepository.class);
                            memory.saveAll("deploy-chat", List.of(new UserMessage("部署后的历史")));
                            assertTrue(
                                    Files.exists(
                                            directory.resolve(
                                                    "chat-memory/love/deploy-chat.kryo")));
                            writeFile(ctx.getBean(ToolCallback[].class));
                            assertEquals(
                                    "部署文件",
                                    Files.readString(
                                            directory.resolve("file/42/deploy-run/deploy.txt")));
                        });
    }

    @Test
    void explicitChatDirectoryOverridesStorageRootWithoutMovingToolFiles() {
        Path relativeRoot = Path.of("").toAbsolutePath().relativize(directory);
        Path chatRoot = relativeRoot.resolve("separate-memory");
        context(relativeRoot)
                .withPropertyValues("chat-memory.base-dir=" + chatRoot)
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            var memory =
                                    ctx.getBean(
                                            "manusChatMemoryRepository",
                                            FileBasedChatMemoryRepository.class);
                            memory.saveAll("deploy-chat", List.of(new UserMessage("独立记忆")));
                            assertTrue(
                                    Files.exists(
                                            directory.resolve(
                                                    "separate-memory/manus/deploy-chat.kryo")));
                            assertFalse(
                                    Files.exists(
                                            directory.resolve(
                                                    "chat-memory/manus/deploy-chat.kryo")));
                            writeFile(ctx.getBean(ToolCallback[].class));
                            assertTrue(
                                    Files.exists(
                                            directory.resolve("file/42/deploy-run/deploy.txt")));
                        });
    }

    @Test
    void defaultOutputIsRelativeAndFilenamesCannotEscapeTheToolDirectory() {
        Path tool = FilePathUtils.toolDirectory("", "file");
        assertFalse(tool.isAbsolute());
        assertFalse(FilePathUtils.resolveFile(tool, "nested\\笔记.txt").isAbsolute());
        assertThrows(
                IllegalArgumentException.class,
                () -> FilePathUtils.resolveFile(tool, "../other.txt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> FilePathUtils.resolveFile(tool, "..\\other.txt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> FilePathUtils.resolveFile(tool, directory.resolve("other.txt").toString()));
    }

    /** 实际启动定时调度器，以短周期验证文件会自动删除而不是必须手动调用清理方法。 */
    @Test
    void schedulerUsesConfiguredRetentionAndPeriodWithoutDeletingChatMemory() {
        context(directory)
                .withPropertyValues(
                        "app.storage.file-retention=PT1S", "app.storage.cleanup-interval=PT0.1S")
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            Path old = directory.resolve("download/expired.txt");
                            Files.createDirectories(old.getParent());
                            Files.writeString(old, "expired");
                            Files.setLastModifiedTime(
                                    old,
                                    java.nio.file.attribute.FileTime.from(
                                            java.time.Instant.now().minusSeconds(2)));
                            Path memory = directory.resolve("chat-memory/manus/keep.kryo");
                            Files.createDirectories(memory.getParent());
                            Files.writeString(memory, "keep");
                            org.awaitility.Awaitility.await()
                                    .atMost(java.time.Duration.ofSeconds(3))
                                    .until(() -> !Files.exists(old));
                            assertTrue(Files.exists(memory));
                        });
    }

    /** 只装配存储配置与工具回调，验证真实的属性占位符及构造参数。 */
    private ApplicationContextRunner context(Path root) {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        AppChatMemoryConfig.class,
                        ToolRegistration.class,
                        GeneratedFileCleanupConfig.class,
                        GeneratedFileServiceImpl.class)
                .withPropertyValues("app.storage.base-dir=" + root, "search-api.api-key=test");
    }

    /** 通过模型实际获得的工具回调写文件，确保工具注册层也使用部署配置。 */
    private void writeFile(ToolCallback[] tools) {
        var callback =
                Arrays.stream(tools)
                        .filter(tool -> tool.getToolDefinition().name().equals("writeFile"))
                        .findFirst()
                        .orElseThrow();
        String result =
                callback.call(
                        "{\"fileName\":\"deploy.txt\",\"content\":\"部署文件\"}",
                        new ToolContext(
                                java.util.Map.of(
                                        GeneratedFileService.USER_ID,
                                        42L,
                                        GeneratedFileService.RUN_ID,
                                        "deploy-run")));
        assertTrue(result.contains("successfully"), result);
    }
}

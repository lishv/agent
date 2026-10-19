package com.xiaoli.aiagent.rag;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** 验证知识库 Markdown 从 JAR 流中加载，不依赖开发机的 src/main/resources 目录。 */
class PackagedDocumentLoadingTest {
    @TempDir Path directory;

    @Test
    void markdownDocumentsAndTheirMetadataLoadFromAJar() throws Exception {
        Path jar = directory.resolve("documents.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("document/"));
            output.closeEntry();
            output.putNextEntry(new JarEntry("document/恋爱常见问题和回答 - 单身篇.md"));
            output.write("# 打包知识库\n\n从应用资源读取中文内容。".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        try (var loader = new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, null)) {
            var documents =
                    new LoveAppDocumentLoader(new PathMatchingResourcePatternResolver(loader))
                            .loadMarkdowns();
            assertFalse(documents.isEmpty());
            assertTrue(
                    documents.stream()
                            .anyMatch(document -> document.getText().contains("从应用资源读取中文内容")));
            assertTrue(
                    documents.stream()
                            .allMatch(
                                    document -> "单身".equals(document.getMetadata().get("status"))));
        }
    }
}

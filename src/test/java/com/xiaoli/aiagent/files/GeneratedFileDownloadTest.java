package com.xiaoli.aiagent.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.xiaoli.aiagent.config.AccountWebConfig;
import com.xiaoli.aiagent.config.StorageProperties;
import com.xiaoli.aiagent.controller.GeneratedFileController;
import com.xiaoli.aiagent.domain.entity.AppUser;
import com.xiaoli.aiagent.exception.*;
import com.xiaoli.aiagent.service.AccountService;
import com.xiaoli.aiagent.service.GeneratedFileService;
import com.xiaoli.aiagent.service.impl.GeneratedFileServiceImpl;
import com.xiaoli.aiagent.tools.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/** 使用真实工具、文件和下载 Controller，验证归属、中文 PDF、过期与磁盘清理。 */
class GeneratedFileDownloadTest {
    @TempDir Path directory;
    private GeneratedFileService files;
    private MockMvc mvc;
    private final ToolContext alice = context(1L, "download-run-1");

    /** 仅替换账号查询，不连接数据库或模型，仍执行项目的登录与账号切换拦截器。 */
    @BeforeEach
    void setUp() {
        files = service(directory.resolve("chat-memory"));
        var accounts = mock(AccountService.class);
        when(accounts.requireUser(any()))
                .thenAnswer(
                        invocation -> {
                            jakarta.servlet.http.HttpServletRequest request =
                                    invocation.getArgument(0);
                            var session = request.getSession(false);
                            if (session == null || session.getAttribute("user") == null) {
                                throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
                            }
                            return session.getAttribute("user");
                        });
        mvc =
                MockMvcBuilders.standaloneSetup(new GeneratedFileController(files, accounts))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .addInterceptors(new AccountWebConfig(accounts).accountInterceptor())
                        .build();
    }

    @Test
    void textToolReturnsAnEncodedAuthenticatedDownloadLinkAndKeepsReadWriteWorking()
            throws Exception {
        var tool = new FileOperationTool(directory.toString(), files);
        String name = "nested/中文 + (1) # [副本].txt";
        String result = tool.writeFile(name, "下载正文", alice);
        assertTrue(result.startsWith("File written successfully"), result);
        assertEquals("下载正文", tool.readFile(name, alice));
        String link = link(result);
        assertFalse(link.contains("sandbox:"));
        assertTrue(link.contains("%2B"), link);
        var response =
                mvc.perform(get(URI.create(link)).contextPath("/api").session(session(1L)))
                        .andExpect(status().isOk())
                        .andExpect(
                                content()
                                        .bytes(
                                                "下载正文"
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                        .andReturn()
                        .getResponse();
        String disposition = response.getHeader("Content-Disposition");
        assertTrue(disposition.contains("attachment"), disposition);
        assertTrue(disposition.contains("filename*=UTF-8''"), disposition);
        assertTrue(disposition.contains("%E4%B8%AD%E6%96%87"), disposition);
        var restored = service(directory.resolve("chat-memory"));
        assertEquals(
                "下载正文",
                Files.readString(restored.requireDownload(1L, "file", "download-run-1", name)));
    }

    @Test
    void realPdfAndImageCanBeDownloadedAndPdfCannotReadAnotherUsersImage() throws Exception {
        Path image =
                files.write(
                        "download",
                        "企鹅.png",
                        alice,
                        path ->
                                ImageIO.write(
                                        new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB),
                                        "png",
                                        path.toFile()));
        mvc.perform(
                        get(URI.create(link(files.downloadLink("download", image, alice))))
                                .contextPath("/api")
                                .session(session(1L)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(Files.readAllBytes(image)));
        var tool =
                new PDFGenerationTool(
                        directory.toString(),
                        PDFGenerationTool.DEFAULT_REGULAR_FONT,
                        PDFGenerationTool.DEFAULT_BOLD_FONT,
                        files);
        String result =
                tool.generatePDF(
                        "中文报告.pdf", "# 分析报告\n\n![企鹅](企鹅.png)", List.of(image.toString()), alice);
        byte[] bytes =
                mvc.perform(get(URI.create(link(result))).contextPath("/api").session(session(1L)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        try (var pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)))) {
            assertEquals(1, pdf.getNumberOfPages());
            assertNotNull(
                    pdf.getPage(1)
                            .getResources()
                            .getResource(com.itextpdf.kernel.pdf.PdfName.XObject));
        }
        String denied =
                tool.generatePDF(
                        "越权.pdf",
                        "![企鹅](企鹅.png)",
                        List.of(image.toString()),
                        context(2L, "download-run-1"));
        assertTrue(denied.startsWith("Error generating PDF:"), denied);
        assertFalse(Files.exists(directory.resolve("pdf/2/download-run-1/越权.pdf")));
    }

    @Test
    void downloadsRequireLoginAndOwnerAndRejectStaleAccountTabs() throws Exception {
        Path file =
                files.write(
                        "download", "photo.jpg", alice, path -> Files.writeString(path, "Alice"));
        String link = link(files.downloadLink("download", file, alice));
        mvc.perform(get(URI.create(link)).contextPath("/api")).andExpect(status().isUnauthorized());
        mvc.perform(get(URI.create(link)).contextPath("/api").session(session(2L)))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get(URI.create(link))
                                .contextPath("/api")
                                .session(session(1L))
                                .header("X-Account-ID", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    void sameFilenameIsIsolatedAcrossUsersAndTasks() throws Exception {
        files.write("file", "same.txt", alice, path -> Files.writeString(path, "first"));
        files.write(
                "file",
                "same.txt",
                context(2L, "download-run-1"),
                path -> Files.writeString(path, "other user"));
        files.write(
                "file",
                "same.txt",
                context(1L, "download-run-2"),
                path -> Files.writeString(path, "other task"));
        assertEquals(
                "first",
                Files.readString(files.requireDownload(1L, "file", "download-run-1", "same.txt")));
        assertEquals(
                "other user",
                Files.readString(files.requireDownload(2L, "file", "download-run-1", "same.txt")));
        assertEquals(
                "other task",
                Files.readString(files.requireDownload(1L, "file", "download-run-2", "same.txt")));
    }

    @Test
    void rejectsTraversalAbsolutePathsReservedFilesAndNonToolDirectories() throws Exception {
        for (String name :
                List.of(
                        "../secret.txt",
                        "..\\secret.txt",
                        directory.resolve("secret.txt").toString(),
                        ".pending-secret.part",
                        "a\n.txt")) {
            mvc.perform(get("/files/file/download-run-1").param("name", name).session(session(1L)))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(
                        get("/files/chat-memory/download-run-1")
                                .param("name", "secret.kryo")
                                .session(session(1L)))
                .andExpect(status().isNotFound());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        files.write(
                                "file", "../secret", alice, path -> Files.writeString(path, "no")));
    }

    @Test
    void expiredFilesCannotBeDownloadedBeforeThePeriodicScanAndReadsDoNotRefreshTheirAge()
            throws Exception {
        Path file =
                files.write(
                        "file", "expired.txt", alice, path -> Files.writeString(path, "content"));
        Instant timestamp = Instant.now().minus(Duration.ofHours(23));
        Files.setLastModifiedTime(file, FileTime.from(timestamp));
        files.requireDownload(1L, "file", "download-run-1", "expired.txt");
        assertEquals(timestamp.toEpochMilli(), Files.getLastModifiedTime(file).toMillis());
        age(file);
        mvc.perform(
                        get("/files/file/download-run-1")
                                .param("name", "expired.txt")
                                .session(session(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("文件不存在或已过期，请重新生成后下载"));
        assertTrue(Files.exists(file));
        assertEquals(1, files.cleanupExpiredFiles());
        assertFalse(Files.exists(file));
    }

    @Test
    void cleanupCoversLegacyAndManagedOutputsButPreservesFreshFilesMemoryAndPackagedResources()
            throws Exception {
        for (String folder : List.of("download", "pdf", "file")) {
            age(write(folder + "/legacy.dat", "old"));
            age(
                    files.write(
                            folder,
                            "nested/managed.dat",
                            alice,
                            path -> Files.writeString(path, "old")));
        }
        Path fresh =
                files.write("file", "fresh.txt", alice, path -> Files.writeString(path, "keep"));
        Path memory = age(write("chat-memory/manus/history.kryo", "memory"));
        Path backup = age(write("chat-memory/manus/context-backups/backup.json", "backup"));
        Path resource = age(write("fonts/font.otf", "font"));
        Path stalePartial = age(write("download/.pending-abandoned.part", "partial"));
        assertEquals(7, files.cleanupExpiredFiles());
        assertTrue(Files.exists(fresh));
        assertTrue(Files.exists(memory));
        assertTrue(Files.exists(backup));
        assertTrue(Files.exists(resource));
        assertFalse(Files.exists(stalePartial));
        assertFalse(Files.exists(directory.resolve("pdf/1/download-run-1/nested")));
        assertEquals(0, files.cleanupExpiredFiles());
    }

    @Test
    void cleanupAndToolsRespectAChatDirectoryConfiguredInsideAToolDirectory() throws Exception {
        Path memoryRoot = directory.resolve("download/private-memory");
        var custom = service(memoryRoot);
        Path memory = age(write("download/private-memory/manus/history.kryo", "memory"));
        Path old = age(write("download/old.jpg", "image"));
        assertEquals(1, custom.cleanupExpiredFiles());
        assertTrue(Files.exists(memory));
        assertFalse(Files.exists(old));
    }

    @Test
    void activeWritesAndPreviousCompletedResultsSurviveCleanupAndFailedReplacement()
            throws Exception {
        Path file =
                files.write(
                        "file", "atomic.txt", alice, path -> Files.writeString(path, "complete"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        files.write(
                                "file",
                                "atomic.txt",
                                alice,
                                path -> {
                                    Files.writeString(path, "unfinished");
                                    age(path);
                                    assertEquals(0, files.cleanupExpiredFiles());
                                    assertTrue(Files.exists(path));
                                    assertEquals(
                                            "complete",
                                            Files.readString(
                                                    files.requireDownload(
                                                            1L,
                                                            "file",
                                                            "download-run-1",
                                                            "atomic.txt")));
                                    throw new IllegalStateException("writer failed");
                                }));
        assertEquals("complete", Files.readString(file));
        try (var paths = Files.walk(directory)) {
            assertFalse(
                    paths.anyMatch(path -> path.getFileName().toString().startsWith(".pending-")));
        }
    }

    @Test
    void linksAndCleanupDoNotFollowSymbolicLinks() throws Exception {
        Path target = age(write("outside/secret.txt", "private"));
        Path link = directory.resolve("download/link");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target.getParent());
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "当前系统不允许创建测试符号链接：" + e.getMessage());
        }
        assertEquals(0, files.cleanupExpiredFiles());
        assertTrue(Files.exists(target));
        Path userLink = directory.resolve("file/1/download-run-1");
        Files.createDirectories(userLink.getParent());
        Files.createSymbolicLink(userLink, target.getParent());
        assertThrows(
                BusinessException.class,
                () -> files.requireDownload(1L, "file", "download-run-1", "secret.txt"));
    }

    @Test
    void springToolSchemaHidesTheServerContextAndRegisteredCallbackUsesIt() {
        var callback =
                java.util.Arrays.stream(
                                ToolCallbacks.from(
                                        new FileOperationTool(directory.toString(), files)))
                        .filter(tool -> tool.getToolDefinition().name().equals("writeFile"))
                        .findFirst()
                        .orElseThrow();
        assertFalse(callback.getToolDefinition().inputSchema().contains("context"));
        String result =
                callback.call(
                        "{\"fileName\":\"callback.txt\",\"content\":\"actual callback\"}", alice);
        assertTrue(result.contains("/api/files/file/download-run-1?name=callback.txt"), result);
        assertTrue(Files.exists(directory.resolve("file/1/download-run-1/callback.txt")));
    }

    @Test
    void rejectsNonPositiveRetentionOrCleanupInterval() {
        var properties = new StorageProperties();
        properties.setFileRetention(Duration.ZERO);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GeneratedFileServiceImpl(
                                properties, "/api", directory.resolve("memory").toString()));
        properties.setFileRetention(Duration.ofHours(24));
        properties.setCleanupInterval(Duration.ofMinutes(-1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GeneratedFileServiceImpl(
                                properties, "/api", directory.resolve("memory").toString()));
    }

    /** 构造能在重启后仅通过磁盘目录恢复下载能力的服务实例。 */
    private GeneratedFileService service(Path memory) {
        var properties = new StorageProperties();
        properties.setBaseDir(directory.toString());
        return new GeneratedFileServiceImpl(properties, "/api", memory.toString());
    }

    /** 模拟由运行服务注入而非模型传入的用户及任务身份。 */
    private static ToolContext context(long user, String run) {
        return new ToolContext(
                Map.of(GeneratedFileService.USER_ID, user, GeneratedFileService.RUN_ID, run));
    }

    /** 创建登录 Session，让下载请求经过真实鉴权拦截器。 */
    private MockHttpSession session(long id) {
        var session = new MockHttpSession();
        var user = new AppUser();
        user.setId(id);
        session.setAttribute("user", user);
        return session;
    }

    /** 从工具返回的 Markdown 提取实际链接，避免测试自行拼接理想化 URL。 */
    private String link(String result) {
        var match = Pattern.compile("\\]\\((/api/files/[^\\n]+)\\)").matcher(result);
        assertTrue(match.find(), result);
        return match.group(1);
    }

    /** 写入仅位于测试目录内的文件。 */
    private Path write(String name, String content) throws Exception {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    /** 将修改时间设为超过 24 小时，避免真实等待。 */
    private Path age(Path file) throws Exception {
        Files.setLastModifiedTime(
                file, FileTime.from(Instant.now().minus(Duration.ofHours(24)).minusSeconds(1)));
        return file;
    }
}

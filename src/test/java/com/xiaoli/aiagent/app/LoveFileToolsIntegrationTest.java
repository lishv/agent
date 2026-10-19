package com.xiaoli.aiagent.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.sun.net.httpserver.HttpServer;
import com.xiaoli.aiagent.config.StorageProperties;
import com.xiaoli.aiagent.conversation.Journal;
import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.service.*;
import com.xiaoli.aiagent.service.impl.ChatApplicationServiceImpl;
import com.xiaoli.aiagent.service.impl.GeneratedFileServiceImpl;
import com.xiaoli.aiagent.tools.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import reactor.core.publisher.Flux;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

/** 真实执行恋爱会话编排、Advisor、工具执行器和文件工具，仅替换模型及知识检索。 */
class LoveFileToolsIntegrationTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final ToolCallingManager toolManager = ToolCallingManager.builder().build();
    private ChatModel model;
    private ContextMemoryService memory;
    private GeneratedFileService files;
    private ChatApplicationServiceImpl chats;
    private HttpServer imageServer;
    private byte[] imageBytes;
    private ToolCallback[] tools;

    /** 使用本机图片响应和测试目录，避免请求外部模型、Pixabay 或真实数据库。 */
    @BeforeEach
    void setUp() throws Exception {
        var imageOutput = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "png", imageOutput);
        imageBytes = imageOutput.toByteArray();
        imageServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        imageServer.createContext(
                "/image.png",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "image/png");
                    exchange.sendResponseHeaders(200, imageBytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(imageBytes);
                    }
                });
        imageServer.start();

        var properties = new StorageProperties();
        properties.setBaseDir(directory.toString());
        files =
                new GeneratedFileServiceImpl(
                        properties, "/api", directory.resolve("chat-memory").toString());
        tools =
                ToolCallbacks.from(
                        new ResourceDownloadTool(directory.toString(), files),
                        new FileOperationTool(directory.toString(), files),
                        new PDFGenerationTool(
                                directory.toString(),
                                PDFGenerationTool.DEFAULT_REGULAR_FONT,
                                PDFGenerationTool.DEFAULT_BOLD_FONT,
                                files));
        model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(DashScopeChatOptions.builder().build());
        memory = mock(ContextMemoryService.class);
        when(memory.open(eq("love"), anyString())).thenAnswer(call -> new ArrayList<Message>());
        when(memory.prepare(eq("love"), anyString(), anyList()))
                .thenAnswer(call -> call.getArgument(2));
        var app = new LoveApp(model, memory);
        ReflectionTestUtils.setField(app, "allTools", tools);
        ReflectionTestUtils.setField(
                app, "toolCallbackProvider", (ToolCallbackProvider) () -> new ToolCallback[0]);
        var vectors = mock(VectorStore.class);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        ReflectionTestUtils.setField(app, "pgVectorVectorStore", vectors);

        var conversations = mock(ConversationService.class);
        when(conversations.begin(anyLong(), eq("love"), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            var journal = mock(Journal.class);
                            when(journal.memoryId())
                                    .thenReturn(call.getArgument(0) + "_" + call.getArgument(2));
                            return journal;
                        });
        chats = new ChatApplicationServiceImpl(app, mock(ManusRunService.class), conversations);
    }

    /** 关闭测试订阅及本机 HTTP 服务。 */
    @AfterEach
    void close() {
        if (chats != null) chats.shutdown();
        if (imageServer != null) imageServer.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"downloadResource", "generatePDF", "writeFile"})
    void loveStreamPassesAuthenticatedContextToEveryFileTool(String toolName) throws Exception {
        var context = new AtomicReference<Map<String, Object>>();
        when(model.stream(any(Prompt.class)))
                .thenAnswer(
                        call -> {
                            Prompt prompt = call.getArgument(0);
                            String result = execute(prompt, toolName, arguments(toolName));
                            context.set(
                                    ((ToolCallingChatOptions) prompt.getOptions())
                                            .getToolContext());
                            return Flux.just(
                                    new ChatResponse(
                                            List.of(new Generation(new AssistantMessage(result)))));
                        });

        String output =
                String.join(
                        "",
                        chats.loveStream(7L, "生成可下载的文件", "file-room")
                                .collectList()
                                .block(Duration.ofSeconds(10)));
        assertEquals(7L, context.get().get(GeneratedFileService.USER_ID));
        String runId = (String) context.get().get(GeneratedFileService.RUN_ID);
        assertNotNull(runId);
        String category =
                switch (toolName) {
                    case "downloadResource" -> "download";
                    case "generatePDF" -> "pdf";
                    default -> "file";
                };
        String name =
                switch (toolName) {
                    case "downloadResource" -> "企鹅.png";
                    case "generatePDF" -> "约会报告.pdf";
                    default -> "建议.txt";
                };
        assertTrue(output.contains("/api/files/" + category + "/" + runId + "?name="), output);
        Path file = files.requireDownload(7L, category, runId, name);
        if (category.equals("download")) assertArrayEquals(imageBytes, Files.readAllBytes(file));
        else if (category.equals("pdf")) {
            try (var pdf = new PdfDocument(new PdfReader(file.toString()))) {
                assertEquals(1, pdf.getNumberOfPages());
            }
        } else assertEquals("先沟通彼此的需求", Files.readString(file));
        assertThrows(
                BusinessException.class, () -> files.requireDownload(8L, category, runId, name));
        verify(memory, timeout(1000)).close("love", "7_file-room");
        assertTrue(
                Arrays.stream(tools)
                        .noneMatch(
                                tool ->
                                        tool.getToolDefinition()
                                                .inputSchema()
                                                .contains("files.userId")));
    }

    @Test
    void repeatedLoveTurnsAndOtherUsersHaveSeparateOutputDirectories() throws Exception {
        var contexts = new ArrayList<Map<String, Object>>();
        var counter = new AtomicInteger();
        when(model.stream(any(Prompt.class)))
                .thenAnswer(
                        call -> {
                            Prompt prompt = call.getArgument(0);
                            String result =
                                    execute(
                                            prompt,
                                            "writeFile",
                                            Map.of(
                                                    "fileName",
                                                    "same.txt",
                                                    "content",
                                                    "result-" + counter.incrementAndGet()));
                            contexts.add(
                                    ((ToolCallingChatOptions) prompt.getOptions())
                                            .getToolContext());
                            return Flux.just(
                                    new ChatResponse(
                                            List.of(new Generation(new AssistantMessage(result)))));
                        });
        for (long user : List.of(7L, 7L, 8L)) {
            chats.loveStream(user, "生成同名文件", "same-room")
                    .collectList()
                    .block(Duration.ofSeconds(5));
        }
        assertEquals(
                3,
                contexts.stream()
                        .map(item -> item.get(GeneratedFileService.RUN_ID))
                        .distinct()
                        .count());
        for (int index = 0; index < contexts.size(); index++) {
            var context = contexts.get(index);
            Path file =
                    files.requireDownload(
                            (Long) context.get(GeneratedFileService.USER_ID),
                            "file",
                            (String) context.get(GeneratedFileService.RUN_ID),
                            "same.txt");
            assertEquals("result-" + (index + 1), Files.readString(file));
        }
    }

    /** 执行模型提出的工具调用；使用真实执行器构造 ToolContext，不能在测试中自行补入身份。 */
    private String execute(Prompt prompt, String toolName, Map<String, Object> arguments)
            throws Exception {
        var response =
                new ChatResponse(
                        List.of(
                                new Generation(
                                        AssistantMessage.builder()
                                                .content("")
                                                .toolCalls(
                                                        List.of(
                                                                new AssistantMessage.ToolCall(
                                                                        "file-call",
                                                                        "function",
                                                                        toolName,
                                                                        json.writeValueAsString(
                                                                                arguments))))
                                                .build())));
        var result = toolManager.executeToolCalls(prompt, response);
        return result.conversationHistory().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(item -> item.getResponses().stream())
                .filter(item -> item.name().equals(toolName))
                .map(ToolResponseMessage.ToolResponse::responseData)
                .findFirst()
                .orElseThrow();
    }

    /** 使用工具公开参数触发下载、中文 PDF 生成或文本写入，不向模型提供身份参数。 */
    private Map<String, Object> arguments(String toolName) {
        return switch (toolName) {
            case "downloadResource" ->
                    Map.of(
                            "url",
                            "http://127.0.0.1:" + imageServer.getAddress().getPort() + "/image.png",
                            "fileName",
                            "企鹅.png");
            case "generatePDF" -> Map.of("fileName", "约会报告.pdf", "content", "# 约会报告\n\n先沟通彼此的需求");
            default -> Map.of("fileName", "建议.txt", "content", "先沟通彼此的需求");
        };
    }
}

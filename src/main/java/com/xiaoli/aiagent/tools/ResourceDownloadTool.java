package com.xiaoli.aiagent.tools;

import cn.hutool.core.io.IoUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.xiaoli.aiagent.constant.FileConstant;
import com.xiaoli.aiagent.service.GeneratedFileService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 流式下载 HTTP(S) 资源，限制网络等待和落盘大小，完整下载后才发布。 */
public class ResourceDownloadTool {
    private final ToolFileStorage storage;

    /** 独立使用时下载到相对目录 tmp/download。 */
    public ResourceDownloadTool() {
        this(FileConstant.FILE_SAVE_DIR);
    }

    /** 指定独立下载的存储根目录。 */
    public ResourceDownloadTool(String directory) {
        this(directory, null);
    }

    /** 应用内复用任务隔离、完整写入、文件有效期与下载链接。 */
    public ResourceDownloadTool(String directory, GeneratedFileService generatedFiles) {
        storage = new ToolFileStorage(directory, "download", generatedFiles);
    }

    /** 校验参数后按大小上限流式下载；超限、HTTP 错误或读取失败不发布半成品。 */
    @Tool(description = "Download an HTTP(S) resource to a file in the current task. "
            + "Streams to disk without loading the entire file into memory. "
            + "timeoutSeconds controls network connect/read timeout (default 30, maximum 120). "
            + "maxSizeMb limits actual received bytes in MiB (default 50, maximum 100). "
            + "Returns the local path and exact Markdown download link. Reuse that link unchanged; never invent sandbox: URLs.")
    public String downloadResource(
            @ToolParam(description = "HTTP(S) URL of the resource") String url,
            @ToolParam(description = "Relative filename to save") String fileName,
            @ToolParam(description = "Network timeout in seconds, 1-120", required = false) Integer timeoutSeconds,
            @ToolParam(description = "Maximum file size in MiB, 1-100", required = false) Integer maxSizeMb,
            ToolContext context) {
        try {
            String address = WebMediaExtractor.safeUrl("", url);
            if (address.isEmpty()) throw new IllegalArgumentException("请提供有效的 HTTP 或 HTTPS 地址");
            int timeout = timeoutSeconds == null ? 30 : timeoutSeconds;
            int maxSize = maxSizeMb == null ? 50 : maxSizeMb;
            if (timeout < 1 || timeout > 120 || maxSize < 1 || maxSize > 100) {
                throw new IllegalArgumentException("timeoutSeconds 必须为 1–120，maxSizeMb 必须为 1–100");
            }
            Path file = storage.write(fileName, context,
                    path -> download(address, path, timeout * 1000, maxSize * 1024L * 1024));
            return "Resource downloaded successfully to: " + storage.describe(file, context);
        } catch (Exception e) {
            return "Error downloading resource: " + e.getMessage();
        }
    }

    /** 保留由应用直接传入任务上下文的调用方式。 */
    public String downloadResource(String url, String fileName, ToolContext context) {
        return downloadResource(url, fileName, null, null, context);
    }

    /** 保留独立调用下载工具的原有方法签名。 */
    public String downloadResource(String url, String fileName) {
        return downloadResource(url, fileName, null);
    }

    /** 同时检查响应声明和实际接收大小，缺少 Content-Length 时也不能无限下载。 */
    private void download(String url, Path file, int timeoutMillis, long maxBytes) throws IOException {
        try (HttpResponse response = HttpUtil.createGet(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; Huiling-Agent/1.0)")
                .setFollowRedirects(true)
                .timeout(timeoutMillis)
                .executeAsync()) {
            if (!response.isOk()) throw new IOException("HTTP " + response.getStatus());
            if (response.contentLength() > maxBytes) throw new IOException("资源大小超过下载上限");
            try (var input = response.bodyStream(); var output = Files.newOutputStream(file)) {
                if (input == null) throw new IOException("响应没有可下载的内容");
                long size = IoUtil.copy(input, output, IoUtil.DEFAULT_BUFFER_SIZE, maxBytes + 1, null);
                if (size > maxBytes) throw new IOException("资源大小超过下载上限");
                if (response.header("Content-Encoding") == null
                        && response.contentLength() >= 0 && size != response.contentLength()) {
                    throw new IOException("下载未完成，实际文件大小与响应声明不一致");
                }
            }
        }
    }
}

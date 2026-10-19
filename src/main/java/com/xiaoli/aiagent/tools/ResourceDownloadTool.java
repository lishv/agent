package com.xiaoli.aiagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;

import com.xiaoli.aiagent.constant.FileConstant;
import com.xiaoli.aiagent.service.GeneratedFileService;
import com.xiaoli.aiagent.util.FilePathUtils;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;

/** 给定URL下载的资源下载工具 */
public class ResourceDownloadTool {
    private final Path downloadDirectory;
    private final GeneratedFileService generatedFiles;

    /** 独立使用时下载到应用启动目录的 tmp/download。 */
    public ResourceDownloadTool() {
        this(FileConstant.FILE_SAVE_DIR);
    }

    /** 根据部署配置选择存储根目录，下载文件归于 download 子目录。 */
    public ResourceDownloadTool(String directory) {
        this(directory, null);
    }

    /** 应用内下载使用任务隔离目录和统一文件有效期。 */
    public ResourceDownloadTool(String directory, GeneratedFileService generatedFiles) {
        downloadDirectory = FilePathUtils.toolDirectory(directory, "download");
        this.generatedFiles = generatedFiles;
    }

    /** 下载完整资源后返回本地路径及真实 HTTP 下载链接，供图片复用和用户下载。 */
    @Tool(
            description =
                    "Download a resource from a URL. Return its local path and the exact Markdown"
                        + " download link. Reuse that link unchanged in the final answer; never"
                        + " invent sandbox: URLs.")
    public String downloadResource(
            @ToolParam(description = "URL of the resource to download") String url,
            @ToolParam(description = "Name of the file to save the downloaded resource")
                    String fileName,
            ToolContext context) {
        try {
            Path filePath;
            if (generatedFiles == null) {
                filePath = FilePathUtils.resolveFile(downloadDirectory, fileName);
                FileUtil.mkdir(filePath.getParent().toFile());
                HttpUtil.downloadFile(url, filePath.toFile());
            } else {
                filePath =
                        generatedFiles.write(
                                "download",
                                fileName,
                                context,
                                path -> HttpUtil.downloadFile(url, path.toFile()));
            }
            return "Resource downloaded successfully to: "
                    + filePath
                    + (generatedFiles == null
                            ? ""
                            : "\n" + generatedFiles.downloadLink("download", filePath, context));
        } catch (Exception e) {
            return "Error downloading resource: " + e.getMessage();
        }
    }

    /** 保留独立调用下载工具的原有方法签名。 */
    public String downloadResource(String url, String fileName) {
        return downloadResource(url, fileName, null);
    }
}

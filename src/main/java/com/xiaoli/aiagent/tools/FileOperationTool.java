package com.xiaoli.aiagent.tools;

import cn.hutool.core.io.FileUtil;

import com.xiaoli.aiagent.constant.FileConstant;
import com.xiaoli.aiagent.service.GeneratedFileService;
import com.xiaoli.aiagent.util.FilePathUtils;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;

/** 文件操作工具类（提供文件读写功能） */
public class FileOperationTool {

    private final Path fileDirectory;
    private final GeneratedFileService generatedFiles;

    /** 独立使用时在应用启动目录的 tmp/file 下读写。 */
    public FileOperationTool() {
        this(FileConstant.FILE_SAVE_DIR);
    }

    /** 使用部署配置的存储根目录，文件读写归于 file 子目录。 */
    public FileOperationTool(String directory) {
        this(directory, null);
    }

    /** 应用内使用统一文件服务，按运行上下文隔离用户与任务并生成下载链接。 */
    public FileOperationTool(String directory, GeneratedFileService generatedFiles) {
        fileDirectory = FilePathUtils.toolDirectory(directory, "file");
        this.generatedFiles = generatedFiles;
    }

    /**
     * 读取文件内容
     *
     * @param fileName 文件名，包含路径
     * @return 文件内容字符串
     */
    @Tool(description = "Read content from a file")
    public String readFile(
            @ToolParam(description = "Name of a file to read in the current task") String fileName,
            ToolContext context) {
        try {
            return FileUtil.readUtf8String(
                    (generatedFiles == null
                                    ? FilePathUtils.resolveFile(fileDirectory, fileName)
                                    : generatedFiles.readableFile("file", fileName, context))
                            .toFile());
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    /** 独立使用工具时保留原有按文件名读取的调用方式。 */
    public String readFile(String fileName) {
        return readFile(fileName, null);
    }

    /**
     * 写入文件内容
     *
     * @param fileName 文件名，包含路径
     * @param content 内容字符串，要写入文件的内容
     * @return 写入成功后的提示信息
     */
    @Tool(description = "Write content to a file")
    public String writeFile(
            @ToolParam(description = "Name of the file to write") String fileName,
            @ToolParam(description = "Content to write to the file") String content,
            ToolContext context) {
        try {
            Path filePath;
            if (generatedFiles == null) {
                filePath = FilePathUtils.resolveFile(fileDirectory, fileName);
                FileUtil.mkdir(filePath.getParent().toFile());
                FileUtil.writeUtf8String(content, filePath.toFile());
            } else {
                filePath =
                        generatedFiles.write(
                                "file",
                                fileName,
                                context,
                                path -> FileUtil.writeUtf8String(content, path.toFile()));
            }
            return "File written successfully to: "
                    + filePath
                    + (generatedFiles == null
                            ? ""
                            : "\n" + generatedFiles.downloadLink("file", filePath, context));
        } catch (Exception e) {
            return "Error writing to file: " + e.getMessage();
        }
    }

    /** 独立使用工具时保留原有写入参数；应用内工具由框架提供运行上下文。 */
    public String writeFile(String fileName, String content) {
        return writeFile(fileName, content, null);
    }
}

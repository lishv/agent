package com.xiaoli.aiagent.tools;

import com.xiaoli.aiagent.service.GeneratedFileService;
import com.xiaoli.aiagent.util.FilePathUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.util.function.ThrowingConsumer;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 文件工具共用的存储入口；应用内的权限、有效期和下载链接仍由文件服务负责。 */
final class ToolFileStorage {
    private final Path directory;
    private final String category;
    private final GeneratedFileService generatedFiles;

    /** 记录工具类别及独立调用时的目录，不提前创建文件。 */
    ToolFileStorage(String root, String category, GeneratedFileService generatedFiles) {
        this.directory = FilePathUtils.toolDirectory(root, category);
        this.category = category;
        this.generatedFiles = generatedFiles;
    }

    /** 应用内校验任务归属和有效期；独立调用限制文件位于工具目录内。 */
    Path read(String name, ToolContext context) {
        return generatedFiles == null
                ? FilePathUtils.resolveFile(directory, name)
                : generatedFiles.readableFile(category, name, context);
    }

    /** 完整写入后才发布文件；失败不覆盖已有成果，也不留下半成品。 */
    Path write(String name, ToolContext context, ThrowingConsumer<Path> writer) throws Exception {
        if (generatedFiles != null) {
            return generatedFiles.write(category, name, context, writer);
        }
        Path file = FilePathUtils.resolveFile(directory, name);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".pending-", ".part");
        try {
            writer.acceptWithException(temporary);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return file;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 返回原有路径格式，应用内同时附上受保护的真实下载链接。 */
    String describe(Path file, ToolContext context) {
        return file + (generatedFiles == null ? "" : "\n" + generatedFiles.downloadLink(category, file, context));
    }

    /** 校验引用文件属于当前用户，独立调用保留原有本地文件读取能力。 */
    Path requireOwnedFile(Path path, ToolContext context) {
        return generatedFiles == null ? path : generatedFiles.requireOwnedFile(path, context);
    }
}

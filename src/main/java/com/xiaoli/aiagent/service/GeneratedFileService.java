package com.xiaoli.aiagent.service;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.util.function.ThrowingConsumer;

import java.nio.file.Path;

/** 工具文件的用户隔离、完整写入、下载与过期清理。 */
public interface GeneratedFileService {
    String USER_ID = "files.userId";
    String RUN_ID = "files.runId";

    /** 在当前用户和任务目录内写临时文件，成功后替换正式文件并开始计算有效期。 */
    Path write(String category, String fileName, ToolContext context, ThrowingConsumer<Path> writer)
            throws Exception;

    /** 定位当前任务的文本文件，限制读取范围并拒绝已过期或未完成的文件。 */
    Path readableFile(String category, String fileName, ToolContext context);

    /** 校验 PDF 引用的本地图片归当前用户所有，允许复用本人其他任务的有效文件。 */
    Path requireOwnedFile(Path file, ToolContext context);

    /** 为当前任务已经写完的文件生成可复制的 Markdown 下载链接。 */
    String downloadLink(String category, Path file, ToolContext context);

    /** 按登录用户、任务与相对文件名查找有效文件，禁止跨用户和目录穿越访问。 */
    Path requireDownload(Long userId, String category, String runId, String fileName);

    /** 删除三个工具输出目录内超过有效期的文件与空子目录，返回删除的文件数。 */
    int cleanupExpiredFiles();
}

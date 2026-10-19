package com.xiaoli.aiagent.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** 单独封装文件树清理规则，避免在下载业务中混入匿名遍历类。 */
@Slf4j
@RequiredArgsConstructor
final class ExpiredToolFileVisitor extends SimpleFileVisitor<Path> {
    private final Path directory;
    private final Path chatMemoryRoot;
    private final Instant cutoff;
    private final Set<Path> pendingWrites;
    private final Object publicationLock;
    private final AtomicInteger removed;

    /** 聊天目录即使被部署配置放进工具目录，也必须跳过。 */
    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        return dir.startsWith(chatMemoryRoot)
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
    }

    /** 删除到期普通文件；与发布共用短锁，避免把刚替换完成的新文件当成旧文件删除。 */
    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
        synchronized (publicationLock) {
            try {
                if (!pendingWrites.contains(file)
                        && !Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)
                                .toInstant()
                                .isAfter(cutoff)
                        && Files.deleteIfExists(file)) removed.incrementAndGet();
            } catch (NoSuchFileException ignored) {
                // 扫描期间其他任务已删除文件，无需重复处理。
            } catch (IOException e) {
                log.warn("过期工具文件暂时无法删除：{}", file, e);
            }
        }
        return FileVisitResult.CONTINUE;
    }

    /** 清理空子目录；目录创建和临时文件登记也使用此锁，避免打断正在开始的写入。 */
    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException error) {
        if (error != null) log.warn("工具目录扫描失败：{}", dir, error);
        if (!dir.equals(directory)) {
            synchronized (publicationLock) {
                try {
                    Files.delete(dir);
                } catch (DirectoryNotEmptyException | NoSuchFileException ignored) {
                    // 仍有有效文件或目录已被删除，继续处理其他目录。
                } catch (IOException e) {
                    log.warn("工具空目录暂时无法删除：{}", dir, e);
                }
            }
        }
        return FileVisitResult.CONTINUE;
    }

    /** 跳过无权读取或刚被移除的条目，其他文件仍继续清理。 */
    @Override
    public FileVisitResult visitFileFailed(Path file, IOException error) {
        log.warn("工具文件扫描失败：{}", file, error);
        return FileVisitResult.CONTINUE;
    }
}

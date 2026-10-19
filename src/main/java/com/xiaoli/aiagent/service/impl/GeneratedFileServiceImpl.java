package com.xiaoli.aiagent.service.impl;

import com.xiaoli.aiagent.config.StorageProperties;
import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.exception.ErrorCode;
import com.xiaoli.aiagent.service.GeneratedFileService;
import com.xiaoli.aiagent.util.FilePathUtils;

import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.function.ThrowingConsumer;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 使用本地文件系统管理工具产物；目录本身记录用户和任务归属，无需新增数据库表。 */
@Slf4j
@Service
public class GeneratedFileServiceImpl implements GeneratedFileService {
    private static final Set<String> CATEGORIES = Set.of("download", "pdf", "file");
    private final Path root;
    private final boolean relativeOutput;
    private final Path chatMemoryRoot;
    private final Duration retention;
    private final String contextPath;
    private final Set<Path> pendingWrites = ConcurrentHashMap.newKeySet();
    private final Object publicationLock = new Object();

    /** 读取部署配置并校验正时长，避免错误配置导致文件刚生成就被清理。 */
    public GeneratedFileServiceImpl(
            StorageProperties properties,
            @Value("${server.servlet.context-path:}") String contextPath,
            @Value("${chat-memory.base-dir:${app.storage.base-dir:./tmp}/chat-memory}")
                    String chatMemoryDirectory) {
        if (properties.getFileRetention() == null
                || properties.getFileRetention().isNegative()
                || properties.getFileRetention().isZero()
                || properties.getCleanupInterval() == null
                || properties.getCleanupInterval().isNegative()
                || properties.getCleanupInterval().isZero()) {
            throw new IllegalArgumentException("文件保留时间和清理周期必须为正时长");
        }
        Path configuredRoot = FilePathUtils.toolDirectory(properties.getBaseDir(), "");
        relativeOutput = !configuredRoot.isAbsolute();
        root = configuredRoot.toAbsolutePath().normalize();
        chatMemoryRoot = FilePathUtils.chatMemoryRoot(chatMemoryDirectory);
        retention = properties.getFileRetention();
        this.contextPath = contextPath.equals("/") ? "" : contextPath.replaceAll("/$", "");
    }

    /** 使用临时文件隔离正在写入的内容；完成后刷新时间并原子替换，失败时移除临时文件。 */
    @Override
    public Path write(
            String category, String fileName, ToolContext context, ThrowingConsumer<Path> writer)
            throws Exception {
        Path file = taskFile(category, fileName, context);
        Path temporary;
        synchronized (publicationLock) {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), ".pending-", ".part");
            pendingWrites.add(temporary);
        }
        try {
            writer.acceptWithException(temporary);
            synchronized (publicationLock) {
                Files.setLastModifiedTime(temporary, FileTime.from(Instant.now()));
                try {
                    Files.move(
                            temporary,
                            file,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return relativeOutput ? Path.of("").toAbsolutePath().relativize(file) : file;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } finally {
                pendingWrites.remove(temporary);
            }
        }
    }

    /** 文本工具只允许读取同一用户、同一任务已写完且尚未过期的文件。 */
    @Override
    public Path readableFile(String category, String fileName, ToolContext context) {
        return requireAvailable(taskFile(category, fileName, context));
    }

    /** 只允许 PDF 读取本人工具目录中的有效文件，不能借生成 PDF 读取其他用户或服务器文件。 */
    @Override
    public Path requireOwnedFile(Path file, ToolContext context) {
        Path actual = file.toAbsolutePath().normalize();
        for (String category : CATEGORIES) {
            Path ownerDirectory = taskDirectory(category, context).getParent();
            if (actual.startsWith(ownerDirectory)) {
                return requireAvailable(
                        checkedFile(ownerDirectory, ownerDirectory.relativize(actual).toString()));
            }
        }
        throw unavailable();
    }

    /** 对路径和文件名编码，链接指向实际 HTTP 接口，不使用 sandbox 或本地文件协议。 */
    @Override
    public String downloadLink(String category, Path file, ToolContext context) {
        Path directory = taskDirectory(category, context);
        Path actual = file.toAbsolutePath().normalize();
        if (!actual.startsWith(directory)) throw new IllegalArgumentException("文件不属于当前任务");
        String name = directory.relativize(actual).toString().replace('\\', '/');
        requireAvailable(checkedFile(directory, name));
        String url =
                UriComponentsBuilder.fromPath(
                                contextPath + "/files/" + category + "/" + runId(context))
                        .queryParam("name", "{name}")
                        .encode()
                        .buildAndExpand(name)
                        .toUriString();
        String label =
                actual.getFileName()
                        .toString()
                        .replace("\\", "\\\\")
                        .replace("[", "\\[")
                        .replace("]", "\\]");
        return "[下载 " + label + "](" + url + ")";
    }

    /** 从登录用户对应的目录查找文件；无权限、过期和不存在统一返回 404。 */
    @Override
    public Path requireDownload(Long userId, String category, String runId, String fileName) {
        try {
            var context = new ToolContext(java.util.Map.of(USER_ID, userId, RUN_ID, runId));
            return requireAvailable(taskFile(category, fileName, context));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw unavailable();
        }
    }

    /** 仅遍历明确列出的工具目录，不跟随符号链接，也不进入单独配置的聊天目录。 */
    @Override
    public int cleanupExpiredFiles() {
        var removed = new AtomicInteger();
        Instant cutoff = Instant.now().minus(retention);
        for (String category : CATEGORIES) {
            Path directory = root.resolve(category);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(root)) continue;
            try {
                Files.walkFileTree(
                        directory,
                        new ExpiredToolFileVisitor(
                                directory,
                                chatMemoryRoot,
                                cutoff,
                                pendingWrites,
                                publicationLock,
                                removed));
            } catch (IOException e) {
                log.warn("工具目录清理失败：{}", directory, e);
            }
        }
        return removed.get();
    }

    /** 使用服务端注入的身份定位工具目录，模型不能通过工具参数指定用户或任务。 */
    private Path taskDirectory(String category, ToolContext context) {
        Object owner = context == null ? null : context.getContext().get(USER_ID);
        if (!CATEGORIES.contains(category) || !(owner instanceof Long userId) || userId < 0) {
            throw new IllegalArgumentException("缺少有效的工具文件用户上下文");
        }
        return root.resolve(category).resolve(userId.toString()).resolve(runId(context));
    }

    /** 校验任务标识，防止上下文中的非法值影响目录定位。 */
    private String runId(ToolContext context) {
        Object value = context == null ? null : context.getContext().get(RUN_ID);
        if (!(value instanceof String id) || !id.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new IllegalArgumentException("缺少有效的工具文件任务上下文");
        }
        return id;
    }

    /** 解析本任务的相对文件名，并复用目录越界及符号链接检查。 */
    private Path taskFile(String category, String fileName, ToolContext context) {
        return checkedFile(taskDirectory(category, context), fileName);
    }

    /** 禁止保留名称、控制字符和符号链接，确保读写仍处于指定工具目录。 */
    private Path checkedFile(Path directory, String name) {
        Path file = FilePathUtils.resolveFile(directory, name);
        if (file.equals(directory)
                || name.chars().anyMatch(Character::isISOControl)
                || file.getFileName().toString().startsWith(".pending-")
                || file.startsWith(chatMemoryRoot)) {
            throw new IllegalArgumentException("文件名不可用");
        }
        Path current = root;
        if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("文件路径不能包含符号链接");
        for (Path part : root.relativize(file)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("文件路径不能包含符号链接");
        }
        return file;
    }

    /** 下载和文本读取共用有效期检查；访问文件不会延长保留时间。 */
    private Path requireAvailable(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getLastModifiedTime(file)
                            .toInstant()
                            .plus(retention)
                            .isAfter(Instant.now())) {
                throw unavailable();
            }
            return file;
        } catch (IOException e) {
            throw unavailable();
        }
    }

    /** 对外不暴露磁盘路径，也不区分文件归属错误与文件已删除。 */
    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.NOT_FOUND_ERROR, "文件不存在或已过期，请重新生成后下载");
    }
}

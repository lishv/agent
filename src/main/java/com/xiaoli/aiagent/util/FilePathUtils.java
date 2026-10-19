package com.xiaoli.aiagent.util;

import com.xiaoli.aiagent.constant.FileConstant;

import java.nio.file.Path;

/** 统一解析部署目录；默认使用相对路径，文件归属检查使用运行时规范化路径。 */
public final class FilePathUtils {
    /** 工具类不创建实例。 */
    private FilePathUtils() {}

    /** 解析聊天根目录，运行时转为规范化路径，以便页面记录、模型记忆和备份共用。 */
    public static Path chatMemoryRoot(String directory) {
        return Path.of(directory.isBlank() ? FileConstant.CHAT_MEMORY_DIR : directory)
                .toAbsolutePath()
                .normalize();
    }

    /** 在配置的存储根目录下取得工具子目录，保留相对配置，便于返回可迁移的输出路径。 */
    public static Path toolDirectory(String directory, String tool) {
        return Path.of(directory.isBlank() ? FileConstant.FILE_SAVE_DIR : directory)
                .resolve(tool)
                .normalize();
    }

    /** 按相对文件名解析工具输出，兼容两种目录分隔符，并限制文件始终位于工具目录内。 */
    public static Path resolveFile(Path directory, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        Path name = Path.of(fileName.replace('\\', '/'));
        Path file = directory.resolve(name).normalize();
        if (name.isAbsolute()
                || !file.toAbsolutePath()
                        .normalize()
                        .startsWith(directory.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("文件名必须位于当前工具目录内");
        }
        return file;
    }
}

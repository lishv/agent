package com.xiaoli.aiagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.xiaoli.aiagent.constant.FileConstant;
import com.xiaoli.aiagent.service.GeneratedFileService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 当前任务的 UTF-8 文本读写、按行查看和精确替换。 */
public class FileOperationTool {
    private static final int MAX_OUTPUT_CHARS = 20_000;
    private static final long MAX_EDIT_BYTES = 2 * 1024 * 1024;
    private final ToolFileStorage storage;

    /** 独立使用时在相对目录 tmp/file 下读写。 */
    public FileOperationTool() {
        this(FileConstant.FILE_SAVE_DIR);
    }

    /** 指定独立使用时的存储根目录。 */
    public FileOperationTool(String directory) {
        this(directory, null);
    }

    /** 应用内复用文件服务的用户隔离、有效期和下载链接。 */
    public FileOperationTool(String directory, GeneratedFileService generatedFiles) {
        storage = new ToolFileStorage(directory, "file", generatedFiles);
    }

    /** 读取 UTF-8 正文，超长内容明确标记截断，避免一次耗尽模型上下文。 */
    @Tool(description = "Read UTF-8 text from a file in the current task. Returns up to 20000 characters; "
            + "use readFileLines to inspect specific lines of a longer file.")
    public String readFile(
            @ToolParam(description = "Relative filename in the current task") String fileName,
            ToolContext context) {
        try (Reader reader = Files.newBufferedReader(storage.read(fileName, context))) {
            char[] buffer = new char[MAX_OUTPUT_CHARS + 1];
            int length = 0;
            int read;
            while (length < buffer.length && (read = reader.read(buffer, length, buffer.length - length)) != -1) {
                length += read;
            }
            String text = new String(buffer, 0, Math.min(length, MAX_OUTPUT_CHARS));
            if (length == 0) return "文件内容为空。";
            return length > MAX_OUTPUT_CHARS
                    ? text + "\n[内容已截断，请使用 readFileLines 按行继续读取]" : text;
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    /** 保留独立读取调用方式。 */
    public String readFile(String fileName) {
        return readFile(fileName, null);
    }

    /** 按行号显示指定区间，每次最多 200 行、20000 字符，便于定位后精确编辑。 */
    @Tool(description = "Read numbered lines of a UTF-8 file up to 2 MiB in the current task. "
            + "startLine is 1-based (default 1), lineCount defaults to 100 and is at most 200. "
            + "Output is capped at 20000 characters and reports the total line count.")
    public String readFileLines(
            @ToolParam(description = "Relative filename in the current task") String fileName,
            @ToolParam(description = "First line, starting from 1", required = false) Integer startLine,
            @ToolParam(description = "Number of lines, 1-200", required = false) Integer lineCount,
            ToolContext context) {
        try {
            int start = startLine == null ? 1 : startLine;
            int count = lineCount == null ? 100 : lineCount;
            if (start < 1 || count < 1 || count > 200) {
                throw new IllegalArgumentException("startLine 必须大于 0，lineCount 必须为 1–200");
            }
            Path path = storage.read(fileName, context);
            requireEditableSize(path);
            List<String> lines = Files.readAllLines(path);
            StringBuilder result = new StringBuilder("总行数：" + lines.size() + "\n");
            int end = (int) Math.min(lines.size(), (long) start - 1 + count);
            for (int index = start - 1; index < end; index++) {
                String line = (index + 1) + ": " + lines.get(index) + "\n";
                int remaining = MAX_OUTPUT_CHARS - result.length();
                if (line.length() > remaining) {
                    return result.append(line, 0, remaining)
                            .append("\n[输出已截断，当前行未显示完整]").toString();
                }
                result.append(line);
            }
            if (start > lines.size()) result.append("指定起始行超出文件范围。");
            else if (end < lines.size()) result.append("下一段从第 ").append(end + 1).append(" 行继续读取。");
            return result.toString();
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    /** 完整写入后发布下载链接；与本实例的精确修改互斥，避免交错覆盖。 */
    @Tool(description = "Create or overwrite a UTF-8 text file in the current task. "
            + "Use editFile to change a specific existing passage. Reuse the returned download link unchanged.")
    public synchronized String writeFile(
            @ToolParam(description = "Relative filename to write") String fileName,
            @ToolParam(description = "Complete text content to write") String content,
            ToolContext context) {
        try {
            if (content == null) throw new IllegalArgumentException("content 不能为 null");
            Path file = storage.write(fileName, context, path -> FileUtil.writeUtf8String(content, path.toFile()));
            return "File written successfully to: " + storage.describe(file, context);
        } catch (Exception e) {
            return "Error writing to file: " + e.getMessage();
        }
    }

    /** 保留独立写入调用方式。 */
    public String writeFile(String fileName, String content) {
        return writeFile(fileName, content, null);
    }

    /** 仅在匹配次数符合预期时替换字面文本，避免误改同名段落或覆盖错误版本。 */
    @Tool(description = "Edit an existing UTF-8 file up to 2 MiB in the current task by exact text replacement. "
            + "Read the file first. oldText is literal text, not regex; expectedOccurrences defaults to 1. "
            + "If the actual match count differs, nothing is written. Empty newText deletes the matched passage.")
    public synchronized String editFile(
            @ToolParam(description = "Relative filename to edit") String fileName,
            @ToolParam(description = "Exact existing text to replace, including whitespace") String oldText,
            @ToolParam(description = "Replacement text; empty string deletes the passage") String newText,
            @ToolParam(description = "Expected match count, default 1", required = false) Integer expectedOccurrences,
            ToolContext context) {
        try {
            int expected = expectedOccurrences == null ? 1 : expectedOccurrences;
            if (oldText == null || oldText.isEmpty() || newText == null || expected < 1) {
                throw new IllegalArgumentException("oldText 不能为空，newText 不能为 null，预期匹配次数必须大于 0");
            }
            Path source = storage.read(fileName, context);
            requireEditableSize(source);
            String content = FileUtil.readUtf8String(source.toFile());
            int matches = StrUtil.count(content, oldText);
            if (matches != expected) {
                throw new IllegalArgumentException("实际匹配 " + matches + " 处，预期 " + expected + " 处；文件未修改");
            }
            long updatedLength = content.length() + (long) matches * (newText.length() - oldText.length());
            if (updatedLength > MAX_EDIT_BYTES) {
                throw new IllegalArgumentException("替换后的文件超过 2 MiB，文件未修改");
            }
            String updated = content.replace(oldText, newText);
            if (updated.getBytes(StandardCharsets.UTF_8).length > MAX_EDIT_BYTES) {
                throw new IllegalArgumentException("替换后的文件超过 2 MiB，文件未修改");
            }
            Path file = storage.write(fileName, context, path -> FileUtil.writeUtf8String(updated, path.toFile()));
            return "File edited successfully (" + matches + " replacements): " + storage.describe(file, context);
        } catch (Exception e) {
            return "Error editing file: " + e.getMessage();
        }
    }

    /** 限制需要完整载入的操作，普通预览仍使用有界流读取。 */
    private void requireEditableSize(Path file) throws IOException {
        if (Files.size(file) > MAX_EDIT_BYTES) {
            throw new IllegalArgumentException("按行读取和精确编辑支持最多 2 MiB 的文本文件");
        }
    }
}

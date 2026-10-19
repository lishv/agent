package com.xiaoli.aiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** 终端操作工具 */
public class TerminalOperationTool {

    // 兼容旧 Windows 程序的 GBK 输出；网页、curl 和现代程序优先使用 UTF-8。
    private static final Charset CMD_CHARSET = Charset.forName("GBK");
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /** 使用当前操作系统的命令解释器执行命令，兼容 Windows 与 Linux/Unix。 */
    @Tool(
            description =
                    """
                    Execute a command using the host platform's command interpreter.
                    Windows uses cmd.exe; Linux and Unix use sh.
                    Use commands appropriate to the host operating system.
                    Do not assume Bash or third-party programs are installed.
                    Use this tool only when terminal execution is necessary.
                    """)
    public String executeTerminalCommand(
            @ToolParam(description = "Command to execute in the terminal") String command) {
        // 输出执行结果
        StringBuilder output = new StringBuilder();
        // 执行命令
        try {
            ProcessBuilder builder = new ProcessBuilder(shellCommand(command, WINDOWS));

            // 合并标准输出和错误输出
            builder.redirectErrorStream(true);
            // 启动进程
            Process process = builder.start();
            try (var stream = process.getInputStream()) {
                output.append(decodeOutput(stream.readAllBytes()));
            }
            // 等待进程退出
            int exitCode = process.waitFor();
            // 检查进程退出码
            if (exitCode != 0) {
                output.append("Command execution failed with exit code: ").append(exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            output.append("Command execution interrupted");
        } catch (IOException e) {
            output.append("Error executing command: ").append(e.getMessage());
        }

        return output.toString();
    }

    /** 根据宿主平台组装命令参数，Unix 采用通用 sh，不依赖 Bash 的安装位置。 */
    static List<String> shellCommand(String command, boolean windows) {
        return windows ? List.of("cmd.exe", "/d", "/c", command) : List.of("sh", "-c", command);
    }

    /** 优先解码 UTF-8，保留旧 Windows 程序输出 GBK 时的兼容处理。 */
    static String decodeOutput(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, CMD_CHARSET);
        }
    }
}

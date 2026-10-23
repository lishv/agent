package com.xiaoli.aiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 在宿主系统执行有时间和输出限制的前台命令。 */
public class TerminalOperationTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_OUTPUT_CHARS = 12_000;
    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final Charset CMD_CHARSET = Charset.forName("GBK");
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /** 为已有 Java 调用保留入口，使用默认工作目录、超时和输出限制。 */
    public String executeTerminalCommand(String command) {
        return executeTerminalCommand(command, null, null, null);
    }

    /** 执行命令并持续消费输出；超时、取消或退出后回收本次启动的进程。 */
    @Tool(description = """
            Execute a foreground command on the server host, not in an isolated sandbox.
            Windows uses cmd.exe; Linux/Unix uses sh. Call getTerminalEnvironment first
            if the operating system or working directory is unknown. Do not assume Bash
            or additional programs are installed. A relative workingDirectory is resolved
            against the server's current working directory. Default timeout is 60 seconds,
            maximum 300; default retained output is 12000 characters, maximum 30000.
            Output reports the exit code, timeout and truncation. Detached/background jobs
            are not supported: tracked child processes are cleaned up when execution ends.
            """)
    public String executeTerminalCommand(
            @ToolParam(description = "Command appropriate to the host operating system") String command,
            @ToolParam(description = "Existing working directory; omit to use the server's current directory", required = false)
                    String workingDirectory,
            @ToolParam(description = "Timeout in seconds, 1-300; default 60", required = false)
                    Integer timeoutSeconds,
            @ToolParam(description = "Maximum retained output characters, 1-30000; default 12000", required = false)
                    Integer maxOutputChars) {
        if (command == null || command.isBlank()) {
            return "Error executing command: command must not be blank";
        }

        final Path directory;
        final int timeout;
        final int outputLimit;
        try {
            directory = resolveDirectory(workingDirectory);
            timeout = validateLimit(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS, "timeoutSeconds");
            outputLimit = validateLimit(maxOutputChars, DEFAULT_OUTPUT_CHARS, MAX_OUTPUT_CHARS, "maxOutputChars");
        } catch (IllegalArgumentException e) {
            return "Error executing command: " + e.getMessage();
        }

        TerminalOutputBuffer output = new TerminalOutputBuffer(outputLimit);
        Map<Long, ProcessHandle> children = new LinkedHashMap<>();
        Process process = null;
        Thread reader = null;
        String status;
        try {
            process = new ProcessBuilder(shellCommand(command, WINDOWS))
                    .directory(directory.toFile()).redirectErrorStream(true).start();
            // 工具不提供交互式标准输入，立即关闭可让等待输入的命令收到 EOF。
            process.getOutputStream().close();
            Process started = process;
            reader = Thread.ofVirtual().name("terminal-output").start(() -> output.read(started.getInputStream()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            boolean finished = waitForProcess(process, children, deadline);
            long remaining = deadline - System.nanoTime();
            if (finished && remaining > 0) {
                reader.join(Duration.ofNanos(remaining));
            }
            if (!finished || reader.isAlive()) {
                status = "Command timed out after " + timeout + " seconds; its process tree was terminated.";
            } else {
                int exitCode = process.exitValue();
                status = exitCode == 0 ? "Exit code: 0"
                        : "Command execution failed with exit code: " + exitCode;
                if (output.readError() != null) {
                    status += "\nError reading command output: " + output.readError();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "Command execution interrupted; its process tree was terminated.";
        } catch (IOException e) {
            status = "Error executing command: " + e.getMessage();
        } finally {
            stopProcessTree(process, children);
            if (reader != null) {
                reader.interrupt();
            }
        }
        return status + "\nWorking directory: " + directory + "\n\n" + output.text();
    }

    /** 提供选择命令所需的宿主信息，不返回环境变量或凭据。 */
    @Tool(description = "Get the server operating system, command shell, current working directory and terminal limits before choosing commands. Does not expose environment variables.")
    public String getTerminalEnvironment() {
        return "Operating system: " + System.getProperty("os.name")
                + "\nShell: " + (WINDOWS ? "cmd.exe" : "sh")
                + "\nWorking directory: " + Path.of("").toAbsolutePath().normalize()
                + "\nTimeout: default " + DEFAULT_TIMEOUT_SECONDS + " seconds, maximum " + MAX_TIMEOUT_SECONDS
                + "\nRetained output: default " + DEFAULT_OUTPUT_CHARS + " characters, maximum " + MAX_OUTPUT_CHARS;
    }

    /** 将相对目录定位到进程工作目录，并要求目标已经存在。 */
    private static Path resolveDirectory(String workingDirectory) {
        Path directory = Path.of(workingDirectory == null || workingDirectory.isBlank() ? "" : workingDirectory)
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("workingDirectory is not an existing directory: " + directory);
        }
        return directory;
    }

    /** 为省略的限制使用默认值，拒绝超出工具允许范围的参数。 */
    private static int validateLimit(Integer value, int defaultValue, int maximum, String name) {
        int result = value == null ? defaultValue : value;
        if (result < 1 || result > maximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
        }
        return result;
    }

    /** 在统一截止时间内等待进程，并记录其子进程以便退出后清理。 */
    private static boolean waitForProcess(Process process, Map<Long, ProcessHandle> children, long deadline)
            throws InterruptedException {
        do {
            rememberChildren(process, children);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return !process.isAlive();
            }
            if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)) {
                return true;
            }
        } while (true);
    }

    /** 保留仍存活的后代句柄，父进程先退出时也能清理已经发现的子进程。 */
    private static void rememberChildren(Process process, Map<Long, ProcessHandle> children) {
        children.values().removeIf(child -> !child.isAlive());
        process.descendants().forEach(child -> children.put(child.pid(), child));
    }

    /** 仅终止本次启动的进程及其已发现的后代，并关闭对应管道。 */
    private static void stopProcessTree(Process process, Map<Long, ProcessHandle> children) {
        if (process == null) {
            return;
        }
        rememberChildren(process, children);
        // 先停止命令解释器，避免它继续启动子进程。
        process.destroyForcibly();
        for (ProcessHandle child : List.copyOf(children.values()).reversed()) {
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly();
        }
        try {
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // 进程退出时管道可能已关闭，不影响已获得的执行结果。
        }
    }

    /** 根据宿主平台组装参数，Unix 使用通用 sh。 */
    static List<String> shellCommand(String command, boolean windows) {
        return windows ? List.of("cmd.exe", "/d", "/c", command) : List.of("sh", "-c", command);
    }

    /** 解码完整输出，优先 UTF-8，兼容旧 Windows 程序的 GBK。 */
    static String decodeOutput(byte[] bytes) {
        return decodeOutput(bytes, false);
    }

    /** 截断时允许末尾 UTF-8 字符不完整，避免将正常中文误判为 GBK。 */
    static String decodeOutput(byte[] bytes, boolean truncated) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer chars = CharBuffer.allocate(bytes.length);
        var result = decoder.decode(ByteBuffer.wrap(bytes), chars, !truncated);
        return result.isError() ? new String(bytes, CMD_CHARSET) : chars.flip().toString();
    }
}

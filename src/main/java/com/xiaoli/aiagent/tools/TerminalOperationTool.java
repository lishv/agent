package com.xiaoli.aiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * 终端操作工具
 */
public class TerminalOperationTool {


     //中文 Windows 的 cmd.exe 通常使用 GBK 编码输出
    private static final Charset CMD_CHARSET = Charset.forName("GBK");

    @Tool(description = """
        Execute a command using Windows cmd.exe.
        Generate Windows CMD-compatible commands.
        Do not assume Bash, Unix commands or third-party programs are installed.
        Use this tool only when terminal execution is necessary.
        """)
    public String executeTerminalCommand(
            @ToolParam(description = "Command to execute in the terminal") String command) {
        // 输出执行结果
        StringBuilder output = new StringBuilder();
        // 执行命令
        try {
            // 当前 Windows cmd.exe 的实际输出是 GBK，因此直接按 GBK 读取。
            ProcessBuilder builder = new ProcessBuilder(
                    "cmd.exe",
                    "/c",
                    command
            );

            // 合并标准输出和错误输出
            builder.redirectErrorStream(true);
            // 启动进程
            Process process = builder.start();
            // 读取进程输出
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            process.getInputStream(),
                            CMD_CHARSET
                    ))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            // 等待进程退出
            int exitCode = process.waitFor();
            // 检查进程退出码
            if (exitCode != 0) {
                output.append("Command execution failed with exit code: ").append(exitCode);
            }
        } catch (IOException | InterruptedException e) {
            output.append("Error executing command: ").append(e.getMessage());
        }

        return output.toString();
    }
}
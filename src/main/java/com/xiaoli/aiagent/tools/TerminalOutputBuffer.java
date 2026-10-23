package com.xiaoli.aiagent.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 持续消费终端输出，只保留有限字节；读取与结果快照可由不同线程执行。 */
final class TerminalOutputBuffer {

    private final int maxChars;
    private final int maxBytes;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private boolean truncated;
    private String readError;

    /** 为 UTF-8 每字符预留最多四字节，字符数限制在最终解码时应用。 */
    TerminalOutputBuffer(int maxChars) {
        this.maxChars = maxChars;
        this.maxBytes = maxChars * 4;
    }

    /** 即使保留量已满也继续读取，防止子进程因管道写满而阻塞。 */
    void read(InputStream input) {
        try (input) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                append(chunk, count);
            }
        } catch (IOException e) {
            synchronized (this) {
                readError = e.getMessage();
            }
        }
    }

    /** 只复制剩余容量范围内的字节，超出的内容直接丢弃。 */
    private synchronized void append(byte[] chunk, int count) {
        int retained = Math.min(count, maxBytes - bytes.size());
        bytes.write(chunk, 0, retained);
        truncated |= retained < count;
    }

    /** 返回安全的输出快照，避免截断 UTF-16 代理对并明确标注截断。 */
    synchronized String text() {
        String text = TerminalOperationTool.decodeOutput(bytes.toByteArray(), truncated);
        boolean shortened = truncated || text.length() > maxChars;
        int end = Math.min(text.length(), maxChars);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + (shortened ? "\n[Output truncated at " + maxChars + " characters]" : "");
    }

    /** 返回读取失败原因，正常 EOF 不作为错误。 */
    synchronized String readError() {
        return readError;
    }
}

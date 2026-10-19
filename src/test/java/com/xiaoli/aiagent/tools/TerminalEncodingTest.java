package com.xiaoli.aiagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalEncodingTest {
    @Test
    void readsUtf8AndLegacyGbkProgramOutput() {
        String text = "自然风景壁纸19，高清";
        assertEquals(text, TerminalOperationTool.decodeOutput(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals(text, TerminalOperationTool.decodeOutput(text.getBytes(Charset.forName("GBK"))));
    }
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void readsUtf8ChineseOutputWithoutMojibake() {
        String output = new TerminalOperationTool().executeTerminalCommand(
                "powershell -NoProfile -Command \"[Console]::OpenStandardOutput().Write([Text.Encoding]::UTF8.GetBytes('中文输出'), 0, 12)\"");
        assertTrue(output.contains("中文输出"), output);
    }
}

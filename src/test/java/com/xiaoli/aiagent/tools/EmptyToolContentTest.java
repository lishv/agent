package com.xiaoli.aiagent.tools;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 工具没有内容时应返回明确说明，供模型判断下一步。 */
class EmptyToolContentTest {
    @TempDir Path directory;

    @Test
    void emptyFileReturnsAnExplanation() {
        var tool = new FileOperationTool(directory.toString());
        tool.writeFile("empty.txt", "");

        assertEquals("文件内容为空。", tool.readFile("empty.txt"));
    }

    @Test
    void searchWithoutMatchesReturnsAnExplanation() {
        var request = mock(HttpRequest.class, RETURNS_SELF);
        var response = mock(HttpResponse.class);
        when(request.execute()).thenReturn(response);
        when(response.isOk()).thenReturn(true);
        when(response.body()).thenReturn("<ol id='b_results'><li class='b_no'>没有匹配结果</li></ol>");
        try (var requests = mockStatic(HttpRequest.class)) {
            requests.when(() -> HttpRequest.get("https://cn.bing.com/search")).thenReturn(request);

            assertTrue(new WebSearchScrapingTool().searchWebScraping("没有匹配的关键词").contains("未找到"));
            verify(response).close();
        }
    }

}

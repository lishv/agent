package com.xiaoli.aiagent.tools;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 深度搜索必须返回真实去重结果，并在搜索源停止分页时明确说明。 */
class WebSearchPaginationTest {
    private final WebSearchScrapingTool tool = new WebSearchScrapingTool();

    @Test
    void collectsFiftyOriginalUrlsAcrossPagesWithoutAdsOrDuplicates() {
        List<String> pages = new ArrayList<>();
        for (int page = 1; page <= 8; page++) {
            String html = resultPage((page - 1) * 7 + 1, 7);
            if (page == 2 || page == 6) html = html.replace("https://example.com/" + ((page - 1) * 7 + 1), "https://example.com/1");
            pages.add(html);
        }
        var fixture = new SearchFixture(pages);
        try (var requests = mockStatic(HttpRequest.class)) {
            fixture.register(requests);

            var results = JSONUtil.parseArray(tool.searchWebScraping("Spring Boot", 50));

            assertEquals(50, results.size());
            assertEquals(50, results.stream().map(item -> JSONUtil.parseObj(item).getStr("link")).distinct().count());
            assertTrue(results.stream().allMatch(item -> JSONUtil.parseObj(item).getStr("link").startsWith("https://example.com/")));
            assertEquals("Spring Boot 1", results.getJSONObject(0).getStr("title"));
            assertEquals("摘要 & 正文 1", results.getJSONObject(0).getStr("snippet"));
            for (int i = 0; i < 8; i++) {
                verify(fixture.requests.get(i)).form("pn", i + 1);
                verify(fixture.responses.get(i)).close();
            }
            requests.verify(() -> HttpRequest.get("https://www.so.com/s"), times(8));
        }
    }

    @Test
    void stopsAtTheRequestedCount() {
        var fixture = new SearchFixture(List.of(resultPage(1, 7), resultPage(8, 7)));
        try (var requests = mockStatic(HttpRequest.class)) {
            fixture.register(requests);
            var results = JSONUtil.parseArray(tool.searchWebScraping("Spring Boot", 11));
            assertEquals(11, results.size());
            assertEquals("https://example.com/11", results.getJSONObject(10).getStr("link"));
            requests.verify(() -> HttpRequest.get("https://www.so.com/s"), times(2));
        }
    }

    @Test
    void repeatedPageReturnsExistingResultsAndAnExplanation() {
        var fixture = new SearchFixture(List.of(resultPage(1, 7), resultPage(1, 7)));
        try (var requests = mockStatic(HttpRequest.class)) {
            fixture.register(requests);
            String result = tool.searchWebScraping("Spring Boot", 50);
            assertTrue(result.contains("7 条"), result);
            assertTrue(result.contains("重复"), result);
            assertEquals(7, JSONUtil.parseArray(result.substring(result.indexOf('\n') + 1)).size());
            requests.verify(() -> HttpRequest.get("https://www.so.com/s"), times(2));
        }
    }

    @Test
    void verificationPageDoesNotDiscardPreviousResults() {
        var fixture = new SearchFixture(List.of(resultPage(1, 7), "<html><title>安全验证</title><body>请完成验证码</body></html>"));
        try (var requests = mockStatic(HttpRequest.class)) {
            fixture.register(requests);
            String result = tool.searchWebScraping("Spring Boot", 50);
            assertTrue(result.contains("7 条"), result);
            assertTrue(result.contains("验证"), result);
            assertTrue(result.contains("https://example.com/1"), result);
            fixture.responses.forEach(response -> verify(response).close());
        }
    }

    @Test
    void httpFailureDoesNotDiscardPreviousResults() {
        var fixture = new SearchFixture(List.of(resultPage(1, 7), ""));
        when(fixture.responses.get(1).isOk()).thenReturn(false);
        when(fixture.responses.get(1).getStatus()).thenReturn(429);
        try (var requests = mockStatic(HttpRequest.class)) {
            fixture.register(requests);
            String result = tool.searchWebScraping("Spring Boot", 50);
            assertTrue(result.contains("7 条"), result);
            assertTrue(result.contains("HTTP 429"), result);
            fixture.responses.forEach(response -> verify(response).close());
        }
    }

    @Test
    void emptySearchReturnsAnExplanation() {
        var fixture = new SearchFixture(List.of("<html><body>没有找到相关结果</body></html>"));
        try (var requests = mockStatic(HttpRequest.class)) {
            fixture.register(requests);
            String result = tool.searchWebScraping("Spring Boot", 50);
            assertTrue(result.contains("未找到"), result);
            assertFalse(result.isBlank());
        }
    }

    @Test
    void rejectsInvalidLimitsBeforeMakingRequests() {
        try (var requests = mockStatic(HttpRequest.class)) {
            assertTrue(tool.searchWebScraping("Spring Boot", 0).contains("1"));
            assertTrue(tool.searchWebScraping("Spring Boot", 51).contains("50"));
            requests.verifyNoInteractions();
        }
    }

    @Test
    void toolSchemaContainsAnOptionalLimitAndKeepsTheOriginalName() {
        var callbacks = ToolCallbacks.from(tool);
        assertEquals(1, callbacks.length);
        var definition = callbacks[0].getToolDefinition();
        assertEquals("searchWebScraping", definition.name());
        var schema = JSONUtil.parseObj(definition.inputSchema());
        assertTrue(schema.getJSONObject("properties").containsKey("maxResults"));
        assertFalse(schema.getJSONArray("required").contains("maxResults"));
    }

    private static String resultPage(int first, int count) {
        StringBuilder html = new StringBuilder("<ul class='result'>")
                .append("<li class='res-list' data-result-type='ad'><h3 class='res-title'><a data-mdurl='https://ads.example/' href='/link?m=ad'>广告</a></h3></li>")
                .append("<li class='res-list' data-result-type='normal'><h3 class='res-title'><a href='/link?m=unknown'>无法确认原始地址</a></h3></li>")
                .append("<li class='res-list' data-result-type='normal'><h3 class='res-title'><a data-mdurl='javascript:alert(1)' href='/link?m=invalid'>无效地址</a></h3></li>");
        for (int i = first; i < first + count; i++) {
            html.append("<li class='res-list' data-result-type='richsum'><h3 class='res-title'><a href='/link?m=tracking-")
                    .append(i).append("' data-mdurl='https://example.com/").append(i)
                    .append("'><em>Spring Boot</em> ").append(i)
                    .append("</a></h3><p class='res-desc'><span data-result-summary>摘要 &amp; 正文 ")
                    .append(i).append("</span></p></li>");
        }
        return html.append("</ul>").toString();
    }

    private static class SearchFixture {
        private final List<HttpRequest> requests = new ArrayList<>();
        private final List<HttpResponse> responses = new ArrayList<>();

        SearchFixture(List<String> pages) {
            for (String page : pages) {
                var request = mock(HttpRequest.class, RETURNS_SELF);
                var response = mock(HttpResponse.class);
                when(request.header(anyString(), anyString())).thenReturn(request);
                when(request.execute()).thenReturn(response);
                when(response.isOk()).thenReturn(true);
                when(response.body()).thenReturn(page);
                requests.add(request);
                responses.add(response);
            }
        }

        void register(org.mockito.MockedStatic<HttpRequest> mocked) {
            var next = new java.util.concurrent.atomic.AtomicInteger();
            mocked.when(() -> HttpRequest.get("https://www.so.com/s"))
                    .thenAnswer(ignored -> requests.get(next.getAndIncrement()));
        }
    }
}

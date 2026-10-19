package com.xiaoli.aiagent.tools;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.ai.support.ToolCallbacks;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSearchScrapingToolTest {

    private final WebSearchScrapingTool tool = new WebSearchScrapingTool();

    @Test
    void returnsTenOrganicResultsWithPlainTextAndIgnoresAdvertisements() {
        StringBuilder html = new StringBuilder("<ol id='b_results'>")
                .append("<li class='b_ad'><h2><a href='https://ad.example/'>广告</a></h2></li>")
                .append("<li class='b_algo'><h2>缺少链接</h2></li>")
                .append("<li class='b_algo'><h2><a href='javascript:void(0)'>无效链接</a></h2></li>");
        for (int i = 1; i <= 12; i++) {
            html.append("<li class='b_algo'><h2><a href='https://example.com/")
                    .append(i).append("'><strong>安徽理工大学</strong> ").append(i)
                    .append("</a></h2><div class='b_caption'><p>简介 &amp; 信息</p></div></li>");
        }
        html.append("</ol>");

        JSONArray results = JSONUtil.parseArray(tool.parseSearchResults(html.toString()));

        assertEquals(10, results.size());
        assertEquals("安徽理工大学 1", results.getJSONObject(0).getStr("title"));
        assertEquals("https://example.com/1", results.getJSONObject(0).getStr("link"));
        assertEquals("简介 & 信息", results.getJSONObject(0).getStr("snippet"));
        assertEquals("https://example.com/10", results.getJSONObject(9).getStr("link"));
    }

    @Test
    void returnsAvailableResultsAndAllowsMissingSnippet() {
        JSONArray results = JSONUtil.parseArray(tool.parseSearchResults(resultPage("https://www.aust.edu.cn/")));

        assertEquals(1, results.size());
        assertEquals("", results.getJSONObject(0).getStr("snippet"));
    }

    @Test
    void decodesBingRedirectsAndPreservesMalformedRedirects() {
        String target = "https://example.com/page?q=中文&lang=zh";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(target.getBytes(StandardCharsets.UTF_8));
        String redirect = "https://www.bing.com/ck/a?u=a1" + encoded + "&ntb=1";
        JSONArray decoded = JSONUtil.parseArray(tool.parseSearchResults(resultPage(redirect)));
        assertEquals(target, decoded.getJSONObject(0).getStr("link"));

        String malformed = "https://www.bing.com/ck/a?u=a1!invalid";
        JSONArray fallback = JSONUtil.parseArray(tool.parseSearchResults(resultPage(malformed)));
        assertEquals(malformed, fallback.getJSONObject(0).getStr("link"));
    }

    @Test
    void distinguishesNoMatchesFromVerificationPages() {
        assertEquals("[]", tool.parseSearchResults("<ol id='b_results'><li class='b_no'>无搜索结果</li></ol>"));
        assertTrue(tool.parseSearchResults("<html><body>请完成验证</body></html>")
                .startsWith("Error searching Bing:"));
    }

    @Test
    void rejectsBlankQueriesWithoutSendingARequest() {
        try (MockedStatic<HttpRequest> requests = mockStatic(HttpRequest.class)) {
            assertTrue(tool.searchWebScraping(null).startsWith("Error searching Bing:"));
            assertTrue(tool.searchWebScraping(" \t\n").startsWith("Error searching Bing:"));
            requests.verifyNoInteractions();
        }
    }

    @Test
    void requestsTenResultsAndClosesResponse() {
        HttpRequest request = mock(HttpRequest.class, RETURNS_SELF);
        HttpResponse response = mock(HttpResponse.class);
        when(request.execute()).thenReturn(response);
        when(response.isOk()).thenReturn(true);
        when(response.body()).thenReturn(resultPage("https://www.aust.edu.cn/"));

        try (MockedStatic<HttpRequest> requests = mockStatic(HttpRequest.class)) {
            requests.when(() -> HttpRequest.get("https://cn.bing.com/search")).thenReturn(request);
            JSONArray results = JSONUtil.parseArray(tool.searchWebScraping("  安徽理工大学 & 招生  "));
            assertEquals(1, results.size());
            verify(request).form("q", "安徽理工大学 & 招生");
            verify(request).form("ensearch", 1);
            verify(request).form("count", 10);
            verify(request).timeout(15000);
            verify(request, times(1)).execute();
            verify(response).close();
        }
    }

    @Test
    void reportsHttpErrorsAndClosesResponse() {
        HttpRequest request = mock(HttpRequest.class, RETURNS_SELF);
        HttpResponse response = mock(HttpResponse.class);
        when(request.execute()).thenReturn(response);
        when(response.getStatus()).thenReturn(429);

        try (MockedStatic<HttpRequest> requests = mockStatic(HttpRequest.class)) {
            requests.when(() -> HttpRequest.get("https://cn.bing.com/search")).thenReturn(request);
            assertEquals("Error searching Bing: HTTP 429", tool.searchWebScraping("安徽理工大学"));
            verify(response, never()).body();
            verify(response).close();
        }
    }

    @Test
    void reportsRequestFailures() {
        HttpRequest request = mock(HttpRequest.class, RETURNS_SELF);
        when(request.execute()).thenThrow(new IllegalStateException("timeout"));

        try (MockedStatic<HttpRequest> requests = mockStatic(HttpRequest.class)) {
            requests.when(() -> HttpRequest.get("https://cn.bing.com/search")).thenReturn(request);
            assertEquals("Error searching Bing: timeout", tool.searchWebScraping("安徽理工大学"));
        }
    }

    @Test
    void registersSearchAsAnAiTool() {
        assertTrue(java.util.Arrays.stream(new ToolRegistration().allTools())
                .anyMatch(callback -> "searchWebScraping".equals(callback.getToolDefinition().name())));
        assertEquals("searchWebScraping", ToolCallbacks.from(tool)[0].getToolDefinition().name());
    }

    private String resultPage(String link) {
        return "<ol id='b_results'><li class='b_algo'><h2><a href='"
                + link.replace("&", "&amp;") + "'>安徽理工大学</a></h2></li></ol>";
    }

    @Test
    void searchWebScrapingTest() {
        String query = "青年到中年,爱情的变化";
        String result = tool.searchWebScraping(query);
        // 真实网络回归测试：非空字符串也可能是错误信息或完全不相关的搜索结果。
        assertNotNull(result);
        assertFalse(result.startsWith("Error searching Bing:"), result);
        JSONArray results = JSONUtil.parseArray(result);
        assertFalse(results.isEmpty(), "Bing should return search results");

    }
}

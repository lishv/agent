package com.xiaoli.imagesearchmcpserver.tools;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用本地响应验证 MCP 的真实返回协议，不调用外部图片接口。 */
class ImageSearchToolTest {
    private final AtomicLong now = new AtomicLong();
    private final ImageSearchTool tool = new ImageSearchTool("test-api-key", now::get);
    private final HttpRequest request = mock(HttpRequest.class, RETURNS_SELF);
    private final HttpResponse response = mock(HttpResponse.class);
    private static final String HITS = """
            {"hits":[{"id":1,"tags":"mountain, lake","webformatURL":"https://cdn.example/one.jpg",
            "largeImageURL":"https://cdn.example/large.jpg","pageURL":"https://pixabay.com/photos/one/",
            "webformatWidth":640,"webformatHeight":360,"user":"author"}]}
            """;
    private MockedStatic<HttpUtil> requestReturning(int status, String body) {
        when(request.execute()).thenReturn(response);
        when(response.getStatus()).thenReturn(status);
        when(response.body()).thenReturn(body);
        var http = mockStatic(HttpUtil.class);
        http.when(() -> HttpUtil.createGet("https://pixabay.com/api/")).thenReturn(request);
        return http;
    }

    @Test void normalizesWebSyntaxAndReturnsDownloadUrlsWithSourceMetadata() {
        try (var http = requestReturning(200, HITS)) {
            var result = JSONUtil.parseObj(tool.searchImage(" site:pixabay.com/photos  mountain   lake filetype:jpg "));
            assertEquals("ok", result.getStr("status"));
            assertEquals("mountain lake", result.getStr("query"));
            assertEquals(1, result.getInt("count"));
            var image = result.getJSONArray("images").getJSONObject(0);
            assertEquals("https://cdn.example/large.jpg", image.getStr("downloadUrl"));
            assertEquals("https://pixabay.com/photos/one/", image.getStr("pageUrl"));
            verify(request).form(argThat((Map<String,Object> params) -> "mountain lake".equals(params.get("q"))
                    && "en".equals(params.get("lang")) && params.get("per_page").equals(10)));
            verify(request).setConnectionTimeout(3000);
            verify(request).setReadTimeout(10000);
            verify(response).close();
            assertFalse(result.toString().contains("test-api-key"));
        }
    }
    @Test void supportsChineseKeywordsAndUsesChineseSearchLanguage() {
        try (var http = requestReturning(200, HITS)) {
            assertEquals("ok", JSONUtil.parseObj(tool.searchImage("校园 风景")).getStr("status"));
            verify(request).form(argThat((Map<String,Object> params) -> "zh".equals(params.get("lang"))
                    && "校园 风景".equals(params.get("q"))));
        }
    }
    @Test void invalidKeywordsNeverReachTheRemoteApi() {
        try (var http = mockStatic(HttpUtil.class)) {
            for (String query : new String[]{"", "site:unsplash.com lake", "https://pixabay.com/photos/",
                    "site:pixabay.com", "intitle:lake", "a".repeat(101)}) {
                assertEquals("invalid_query", JSONUtil.parseObj(tool.searchImage(query)).getStr("status"));
            }
            assertEquals("invalid_query", JSONUtil.parseObj(tool.searchImage(null)).getStr("status"));
            http.verifyNoInteractions();
        }
    }
    @Test void missingApiCredentialIsReportedWithoutSendingARequest() {
        try (var http = mockStatic(HttpUtil.class)) {
            assertEquals("not_configured", JSONUtil.parseObj(new ImageSearchTool("").searchImage("lake")).getStr("status"));
            http.verifyNoInteractions();
        }
    }
    @Test void emptyHitsAreExplicitAndCachedInsteadOfReturningBlankText() {
        try (var http = requestReturning(200, "{\"hits\":[]}")) {
            var first = JSONUtil.parseObj(tool.searchImage("unmatched"));
            assertEquals("no_results", first.getStr("status"));
            assertFalse(first.getStr("message").isBlank());
            assertTrue(JSONUtil.parseObj(tool.searchImage("unmatched")).getBool("cached"));
            verify(request, times(1)).execute();
        }
    }
    @Test void cachesMatchesForTwentyFourHoursAndRefreshesExpiredLinks() {
        try (var http = requestReturning(200, HITS)) {
            tool.searchImage("lake");
            now.addAndGet(1000);
            assertTrue(JSONUtil.parseObj(tool.searchImage("site:pixabay.com lake")).getBool("cached"));
            verify(request, times(1)).execute();
            now.set(24 * 60 * 60 * 1000L + 1);
            assertFalse(JSONUtil.parseObj(tool.searchImage("lake")).getBool("cached"));
            verify(request, times(2)).execute();
        }
    }
    @Test void rateLimitUsesProviderDelayAndBlocksFurtherRequestsUntilExpiry() {
        try (var http = requestReturning(429, "not JSON")) {
            when(response.header("X-RateLimit-Reset")).thenReturn("12");
            assertEquals(12, JSONUtil.parseObj(tool.searchImage("lake")).getInt("retryAfterSeconds"));
            var second = JSONUtil.parseObj(tool.searchImage("ocean"));
            assertEquals("rate_limited", second.getStr("status"));
            verify(request, times(1)).execute();
            verify(response, never()).body();
            verify(response).close();
            now.addAndGet(13000);
            when(response.getStatus()).thenReturn(200);
            when(response.body()).thenReturn(HITS);
            assertEquals("ok", JSONUtil.parseObj(tool.searchImage("ocean")).getStr("status"));
            verify(request, times(2)).execute();
        }
    }
    @Test void rateLimitFallsBackToSixtySecondsWhenHeadersAreMissing() {
        try (var http = requestReturning(429, "")) {
            assertEquals(60, JSONUtil.parseObj(tool.searchImage("lake")).getInt("retryAfterSeconds"));
        }
    }
    @Test void reportsHttpAndMalformedResponsesWithoutLeakingRawBodies() {
        try (var http = requestReturning(403, "sensitive-key-data")) {
            var error = JSONUtil.parseObj(tool.searchImage("lake"));
            assertEquals("upstream_error", error.getStr("status"));
            assertEquals(403, error.getInt("httpStatus"));
            assertFalse(error.toString().contains("sensitive-key-data"));
            when(response.getStatus()).thenReturn(200);
            for (String body : new String[]{"not-json sensitive-key-data", "{\"unexpected\":true}"}) {
                when(response.body()).thenReturn(body);
                assertEquals("upstream_error", JSONUtil.parseObj(tool.searchImage("lake")).getStr("status"));
            }
        }
    }
    @Test void transportFailuresDoNotExposeApiKeysOrRequestUrls() {
        try (var http = requestReturning(200, HITS)) {
            when(request.execute()).thenThrow(new IllegalStateException("https://pixabay.com/api/?key=test-api-key"));
            String error = tool.searchImage("lake");
            assertEquals("network_error", JSONUtil.parseObj(error).getStr("status"));
            assertFalse(error.contains("test-api-key"));
            assertFalse(error.contains("https://pixabay.com/api/"));
        }
    }
    @Test void filtersInvalidAddressesAndFallsBackToMediumSizeDownload() {
        try (var http = requestReturning(200, """
                {"hits":[{"webformatURL":"javascript:alert(1)"},{"webformatURL":"https://cdn.example/safe.jpg"}]}
                """)) {
            var result = JSONUtil.parseObj(tool.searchImage("lake"));
            assertEquals(1, result.getInt("count"));
            assertEquals("https://cdn.example/safe.jpg", result.getJSONArray("images").getJSONObject(0).getStr("downloadUrl"));
        }
    }
}

package com.xiaoli.aiagent.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;

import org.junit.jupiter.api.Test;

import java.util.Map;

class BingImageSearchClientTest {
    @Test
    void extractsOriginalImageAndSourcePageFiltersInvalidUrlsAndCachesCandidates() {
        var request = mock(HttpRequest.class, RETURNS_SELF);
        when(request.header(anyString(), anyString())).thenReturn(request);
        var response = mock(HttpResponse.class);
        try (var http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.createGet(BingImageSearchClient.SEARCH_URL))
                    .thenReturn(request);
            when(request.execute()).thenReturn(response);
            when(response.isOk()).thenReturn(true);
            when(response.body())
                    .thenReturn(
                            """
<a class='iusc' m='{&quot;murl&quot;:&quot;https://images.example/original.jpg&quot;,
&quot;purl&quot;:&quot;https://source.example/cat&quot;,&quot;t&quot;:&quot;kitten&quot;}'></a>
<a class='iusc' m='{"murl":"javascript:alert(1)","purl":"https://source.example/"}'></a>
<a class='iusc' m='not-json'></a>
""");
            var client = new BingImageSearchClient();
            var images = client.search("kitten", 3840, 2160);
            assertEquals(1, images.size());
            assertEquals(
                    "https://images.example/original.jpg",
                    images.getJSONObject(0).getStr("downloadUrl"));
            assertEquals("https://source.example/cat", images.getJSONObject(0).getStr("pageUrl"));
            verify(request)
                    .form(
                            argThat(
                                    (Map<String, Object> params) ->
                                            params.get("q").equals("kitten 4K壁纸")
                                                    && params.get("adlt").equals("strict")));
            client.search("kitten", 3840, 2160);
            verify(request, times(1)).execute();
            client.search("kitten", 0, 0);
            verify(request, times(2)).execute();
        }
    }

    @Test
    void rateLimitStopsSubsequentUncachedRequests() {
        var request = mock(HttpRequest.class, RETURNS_SELF);
        when(request.header(anyString(), anyString())).thenReturn(request);
        var response = mock(HttpResponse.class);
        try (var http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.createGet(BingImageSearchClient.SEARCH_URL))
                    .thenReturn(request);
            when(request.execute()).thenReturn(response);
            when(response.getStatus()).thenReturn(429);
            var client = new BingImageSearchClient();
            assertThrows(IllegalStateException.class, () -> client.search("kitten", 0, 0));
            assertThrows(IllegalStateException.class, () -> client.search("lake", 0, 0));
            verify(request, times(1)).execute();
        }
    }
}

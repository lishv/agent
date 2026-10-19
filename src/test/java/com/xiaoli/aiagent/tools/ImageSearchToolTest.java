package com.xiaoli.aiagent.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.atomic.AtomicLong;

/** 重现 MCP 超时和伪 4K 问题，外部图库及图片头使用本地替身。 */
class ImageSearchToolTest {
    private final ToolCallback remote = mock(ToolCallback.class);
    private final BingImageSearchClient fallback = mock(BingImageSearchClient.class);
    private final ImageMetadataReader metadata = mock(ImageMetadataReader.class);
    private final AtomicLong clock = new AtomicLong();

    private ImageSearchTool tool() {
        when(remote.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("searchImage")
                                .description("Pixabay search")
                                .inputSchema("{}")
                                .build());
        return new ImageSearchTool(remote, fallback, metadata, clock::get);
    }

    private JSONArray images(String source, int count) {
        var images = JSONUtil.createArray();
        for (int i = 0; i < count; i++)
            images.add(
                    JSONUtil.createObj()
                            .set("url", "https://images.example/" + source + i + ".jpg")
                            .set("downloadUrl", "https://images.example/" + source + i + ".jpg")
                            .set("pageUrl", "https://source.example/" + i)
                            .set("source", source)
                            .set("tags", "kitten"));
        return images;
    }

    @Test
    void timeoutFallsBackOnceAndDoesNotRetryTheBrokenMcpDuringTheSameTask() {
        var tool = tool();
        when(remote.call(anyString()))
                .thenThrow(new IllegalStateException("TimeoutException: 20000ms"));
        when(fallback.search(anyString(), anyInt(), anyInt())).thenReturn(images("Bing", 4));
        when(metadata.read(anyString(), anyInt())).thenReturn(new ImageDimensions(1920, 1080));

        var first = JSONUtil.parseObj(tool.searchImage("kitten", 3, null, null));
        assertEquals("ok", first.getStr("status"));
        assertEquals(3, first.getInt("count"));
        assertTrue(first.getBool("complete"));
        assertTrue(first.getBool("fallbackUsed"));
        assertFalse(first.toString().contains("TimeoutException"));
        tool.searchImage("cat", 3, null, null);
        verify(remote, times(1)).call(anyString());
    }

    @Test
    void highResolutionSearchKeepsPreviewsButNeverLabels1280PixelsAs4k() {
        var tool = tool();
        when(fallback.search(eq(""), eq(3840), eq(2160))).thenReturn(images("Bing", 8));
        when(metadata.read(anyString(), anyInt())).thenReturn(new ImageDimensions(3840, 2160));
        when(metadata.read(eq("https://images.example/Bing0.jpg"), anyInt()))
                .thenReturn(new ImageDimensions(1280, 720));
        var result = JSONUtil.parseObj(tool.searchImage("4K wallpaper", 5, null, null));
        assertEquals(5, result.getInt("count"));
        assertFalse(result.getBool("allMeetRequestedResolution"));
        var preview = result.getJSONArray("images").getJSONObject(0);
        assertEquals(1280, preview.getInt("width"));
        assertTrue(preview.getBool("isPreview"));
        assertFalse(preview.getBool("meetsRequestedResolution"));
        for (Object item : result.getJSONArray("images").subList(1, 5)) {
            var image = JSONUtil.parseObj(item);
            assertEquals(3840, image.getInt("width"));
            assertEquals(2160, image.getInt("height"));
            assertTrue(image.getBool("resolutionVerified"));
            assertTrue(image.getBool("meetsRequestedResolution"));
        }
        verifyNoInteractions(remote);
    }

    @Test
    void successfulMcpKeepsTheRequestedCountAndDoesNotContactTheFallback() {
        var tool = tool();
        when(remote.call(anyString()))
                .thenReturn(
                        JSONUtil.createObj()
                                .set("status", "ok")
                                .set("images", images("Pixabay", 10))
                                .toString());
        when(metadata.read(anyString(), anyInt())).thenReturn(new ImageDimensions(1280, 853));
        var result = JSONUtil.parseObj(tool.searchImage("kitten", 3, null, null));
        assertEquals(3, result.getInt("count"));
        assertEquals("Pixabay", result.getStr("source"));
        assertFalse(result.getBool("fallbackUsed"));
        verifyNoInteractions(fallback);
    }

    @Test
    void insufficientResultsStayExplicitAndDuplicateOrNonImageUrlsDoNotCount() {
        var tool = tool();
        when(remote.call(anyString())).thenReturn("{\"status\":\"no_results\",\"images\":[]}");
        var candidates = images("Bing", 2);
        candidates.add(candidates.getJSONObject(0));
        when(fallback.search(anyString(), anyInt(), anyInt())).thenReturn(candidates);
        when(metadata.read(anyString(), anyInt())).thenReturn(null);
        when(metadata.read(eq("https://images.example/Bing0.jpg"), anyInt()))
                .thenReturn(new ImageDimensions(1600, 1200));
        var result = JSONUtil.parseObj(tool.searchImage("kitten", 3, 0, 0));
        assertEquals(1, result.getInt("count"));
        assertFalse(result.getBool("complete"));
        assertTrue(result.getStr("message").contains("1/3"));
    }

    @Test
    void providerFailuresNeverExposeExceptionTextAndInvalidInputDoesNotCallAnyProvider() {
        var tool = tool();
        for (String query : new String[] {"", "https://example.test", "x".repeat(101)}) {
            assertEquals(
                    "invalid_query",
                    JSONUtil.parseObj(tool.searchImage(query, 3, null, null)).getStr("status"));
        }
        when(remote.call(anyString())).thenThrow(new IllegalStateException("secret-request-url"));
        when(fallback.search(anyString(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("secret-token"));
        var result = tool.searchImage("kitten", 3, 0, 0);
        assertTrue(result.contains("network_error"));
        assertFalse(result.contains("secret"));
    }

    @Test
    void sharedToolWiringKeepsMapsAndAddsOptionalImageCountAndPixelParameters() {
        tool();
        var map = mock(ToolCallback.class);
        when(map.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("maps_text_search")
                                .description("map")
                                .inputSchema("{}")
                                .build());
        var callbacks =
                AiToolCallbacks.merge(new ToolCallback[0], new ToolCallback[] {remote, map});
        assertEquals(2, callbacks.length);
        assertSame(map, callbacks[1]);
        assertEquals("searchImage", callbacks[0].getToolDefinition().name());
        var schema = JSONUtil.parseObj(callbacks[0].getToolDefinition().inputSchema());
        assertTrue(schema.getJSONObject("properties").containsKey("count"));
        assertTrue(schema.getJSONObject("properties").containsKey("minWidth"));
        assertEquals(
                "invalid_query",
                JSONUtil.parseObj(callbacks[0].call("{\"query\":\"\"}")).getStr("status"));
    }
}

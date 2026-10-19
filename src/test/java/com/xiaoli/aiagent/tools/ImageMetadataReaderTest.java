package com.xiaoli.aiagent.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

class ImageMetadataReaderTest {
    @Test
    void readsActualPngHeaderWithoutLoadingPixelsOrUsingSearchMetadata() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12, 7, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var request = mock(HttpRequest.class, RETURNS_SELF);
        when(request.header(anyString(), anyString())).thenReturn(request);
        var response = mock(HttpResponse.class);
        try (var http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.createGet("https://images.example/cat.png"))
                    .thenReturn(request);
            when(request.executeAsync()).thenReturn(response);
            when(response.isOk()).thenReturn(true);
            when(response.bodyStream()).thenReturn(new ByteArrayInputStream(bytes.toByteArray()));
            assertEquals(
                    new ImageDimensions(12, 7),
                    new ImageMetadataReader().read("https://images.example/cat.png", 700));
            verify(request).timeout(700);
            verify(request).executeAsync();
            verify(response, never()).bodyBytes();
            verify(response).close();
        }
    }

    @Test
    void rejectsHtmlErrorPagesAndTransportFailures() {
        var request = mock(HttpRequest.class, RETURNS_SELF);
        when(request.header(anyString(), anyString())).thenReturn(request);
        var response = mock(HttpResponse.class);
        try (var http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.createGet("https://images.example/missing.jpg"))
                    .thenReturn(request);
            when(request.executeAsync()).thenReturn(response);
            when(response.isOk()).thenReturn(true);
            when(response.bodyStream())
                    .thenReturn(new ByteArrayInputStream("<html>not an image</html>".getBytes()));
            assertNull(new ImageMetadataReader().read("https://images.example/missing.jpg", 500));
            when(request.executeAsync())
                    .thenThrow(new IllegalStateException("private-url-signature"));
            assertNull(new ImageMetadataReader().read("https://images.example/missing.jpg", 500));
        }
    }
}

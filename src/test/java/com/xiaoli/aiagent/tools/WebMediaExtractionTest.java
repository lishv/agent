package com.xiaoli.aiagent.tools;

import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class WebMediaExtractionTest {
    @Test void extractsRelativeLazyResponsiveMediaAndShareMetadataWithoutDuplicateUrls() {
        var document = Jsoup.parse("""
                <html><head><title>校园资源</title><meta property="og:image" content="/cover.jpg">
                <meta property="og:video:url" content="/share.mp4"><meta property="og:audio" content="/share.mp3"></head>
                <body><main><p>校园介绍</p><img src="/cover.jpg" alt="校园封面">
                <img data-src="/lazy.webp" data-srcset="/small.jpg 1x, /large.jpg 2x">
                <picture><source srcset="/responsive.avif 640w"></picture>
                <video poster="/poster.png"><source src="../movies/tour.mp4" type="video/mp4"></video>
                <audio><source src="//cdn.example/music.mp3" type="audio/mpeg"></audio>
                <a href="/music.flac?download=1">背景音乐</a><iframe src="/embed/player" title="视频播放器"></iframe>
                </main></body></html>
                """, "https://example.test/articles/post");
        String content = new WebScrapingTool().readableContent(document);
        for (String url : new String[]{"https://example.test/cover.jpg", "https://example.test/lazy.webp",
                "https://example.test/small.jpg", "https://example.test/large.jpg", "https://example.test/responsive.avif",
                "https://example.test/poster.png", "https://example.test/movies/tour.mp4", "https://example.test/share.mp4",
                "https://example.test/share.mp3", "https://cdn.example/music.mp3", "https://example.test/music.flac?download=1"})
            assertTrue(content.contains(url), url);
        assertEquals(1, content.split("https://example.test/cover.jpg", -1).length - 1);
        assertTrue(content.contains("视频链接")); assertTrue(content.contains("音频链接"));
        assertTrue(content.contains("嵌入页面（非媒体直链）"));
        assertTrue(content.contains("https://example.test/embed/player"));
    }
    @Test void rejectsInlineBlobAndScriptAddressesAndEscapesMediaLabels() {
        var document = Jsoup.parse("""
                <img src="javascript:alert(1)"><img src="data:image/png;base64,AAAA"><img src="blob:https://example.test/123">
                <img src="/valid.png" alt="[危险](javascript:alert(1))"><script>secret()</script>
                """, "https://example.test/");
        String links = WebMediaExtractor.extract(document);
        assertTrue(links.contains("https://example.test/valid.png"));
        assertFalse(links.contains("[危险](javascript:")); assertFalse(links.contains("data:image"));
        assertFalse(links.contains("blob:")); assertFalse(links.contains("secret()"));
    }
    @Test void limitsEachMediaGroupAndReportsTruncation() {
        StringBuilder html = new StringBuilder();
        for (int i=0;i<35;i++) html.append("<img src='/image-").append(i).append(".png'>");
        String content = WebMediaExtractor.extract(Jsoup.parse(html.toString(),"https://example.test/"));
        assertTrue(content.contains("图片链接（30 条）")); assertTrue(content.contains("每类最多展示 30 条"));
        assertFalse(content.contains("/image-30.png"));
    }
    @Test void mediaOnlyPagesAndVerificationPagesAreReportedAccurately() {
        String media = new WebScrapingTool().readableContent(Jsoup.parse("<img src='/photo.jpg'>","https://example.test/"));
        assertTrue(media.contains("已提取页面暴露的媒体链接"));
        String blocked = new WebScrapingTool().readableContent(Jsoup.parse("<title>百度安全验证</title><img src='/captcha.png'>",
                "https://example.test/"));
        assertTrue(blocked.contains("网页抓取受限")); assertFalse(blocked.contains("captcha.png"));
    }
    @Test void fetchesHtmlIdentifiesBinaryMediaAndDistinguishesHttpFailures() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = path.equals("/blocked") ? 403 : 200;
            String type = path.equals("/photo") ? "image/png" : path.equals("/music") ? "audio/mpeg" : "text/html; charset=UTF-8";
            byte[] body = "<title>资源页面</title><main><img src='/public.jpg'><video src='/tour.mp4'></video></main>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",type);
            exchange.sendResponseHeaders(status,body.length);
            try (var output=exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var tool = new WebScrapingTool();
            assertTrue(tool.scrapeWebPage(base+"/page").contains(base+"/public.jpg"));
            assertTrue(tool.scrapeWebPage(base+"/photo").contains("资源类型：图片"));
            assertTrue(tool.scrapeWebPage(base+"/music").contains("资源类型：音频"));
            assertTrue(tool.scrapeWebPage(base+"/blocked").contains("HTTP 403"));
            assertTrue(tool.scrapeWebPage("javascript:alert(1)").contains("有效的 HTTP"));
        } finally { server.stop(0); }
    }
}

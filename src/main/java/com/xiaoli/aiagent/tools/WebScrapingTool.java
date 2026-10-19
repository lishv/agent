package com.xiaoli.aiagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.HttpStatusException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * URL网页抓取工具
 */
public class WebScrapingTool {

    @Tool(description = "Read a public web page and return title, readable text, and discovered image, video and audio URLs. "
            + "Extracts HTML media tags, lazy-loading attributes, srcset, direct media links and Open Graph metadata. "
            + "Embedded player pages are labeled separately and are not downloadable media files. "
            + "Can identify a direct media URL. Does not execute JavaScript; verification pages or inaccessible resources "
            + "must be reported as limitations. Use downloadResource with an actual media URL to save a file.")
    public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
        try {
            if (WebMediaExtractor.safeUrl(url, url).isEmpty()) return "网页抓取失败：请提供有效的 HTTP 或 HTTPS 地址。";
            var response = Jsoup.connect(url).timeout(15000).maxBodySize(2 * 1024 * 1024)
                    .ignoreContentType(true).execute();
            String type = response.contentType() == null ? "" : response.contentType().toLowerCase(java.util.Locale.ROOT);
            String kind = type.startsWith("image/") ? "图片" : type.startsWith("video/") ? "视频"
                    : type.startsWith("audio/") ? "音频" : type.contains("mpegurl") || type.contains("dash+xml") ? "视频播放列表"
                    : type.startsWith("application/octet-stream") ? WebMediaExtractor.kindFromUrl(response.url().toString()) : null;
            if (kind != null) {
                response.bodyStream().close();
                return "资源类型：" + kind + "\n资源地址："
                        + WebMediaExtractor.safeUrl(response.url().toString(), response.url().toString())
                        + "\n已识别资源地址，可交给下载工具；播放列表不是单个完整媒体文件。";
            }
            if (!type.isEmpty() && !type.contains("html") && !type.contains("xml") && !type.startsWith("text/")) {
                response.bodyStream().close();
                return "网页抓取失败：响应类型为 " + type + "，当前工具仅解析网页和识别媒体资源。";
            }
            return readableContent(response.parse());
        } catch (HttpStatusException e) {
            return "网页抓取失败：HTTP " + e.getStatusCode() + "，网站拒绝访问或服务暂时不可用。";
        } catch (Exception e) {
            return "网页抓取失败：连接失败或超时，请检查地址并稍后重试。";
        }
    }

    String readableContent(Document document) {
        String title = document.title();
        String url = document.location();
        if (title.matches(".*(?:安全验证|验证码|Access Denied|Just a moment).*"))
            return "网页抓取受限：网站返回验证或访问限制页面，当前没有获取到所需资源。\n网页地址：" + url;
        String media = WebMediaExtractor.extract(document);
        document.select("script,style,noscript,template,svg").remove();
        Element body = document.selectFirst("main,article,[role=main]");
        if (body == null) body = document.body();
        body.select("p,li,h1,h2,h3,h4,h5,h6,blockquote,pre,tr,section").forEach(block -> block.appendText("\n"));
        String text = body.wholeText().replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        if (text.isBlank()) text = media.contains("链接（") ? "页面没有可读正文，已提取页面暴露的媒体链接。"
                : "页面没有可读正文，可能需要客户端加载；当前抓取无法确认页面数据。";
        return "网页标题：" + title + "\n网页地址：" + url + "\n\n" + text + media;
    }
}

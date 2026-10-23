package com.xiaoli.aiagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * URL网页抓取工具
 */
public class WebScrapingTool {
    private static final int TIMEOUT_MILLIS = 15000;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int DEFAULT_TEXT_LIMIT = 8000;
    private static final int MAX_TEXT_LIMIT = 12000;
    private static final int PAGE_LINK_LIMIT = 20;
    private static final int PAGE_LINK_TEXT_LIMIT = 8000;

    /** 读取首段正文与页面公开的资源链接；长文可通过 readWebPage 分段继续读取。 */
    @Tool(description = "Read a public web page and return title, readable text, and discovered image, video and audio URLs. "
            + "Extracts HTML media tags, lazy-loading attributes, srcset, direct media links and Open Graph metadata. "
            + "Embedded player pages are labeled separately and are not downloadable media files. "
            + "Can identify a direct media URL. Does not execute JavaScript; verification pages or inaccessible resources "
            + "must be reported as limitations. Returns at most 8000 text characters; use readWebPage "
            + "with offset or keyword for further sections. Use downloadResource with an actual media URL to save a file.")
    public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
        return fetchPage(url, 0, DEFAULT_TEXT_LIMIT, null);
    }

    /** 按正文字符偏移或精确关键词定位长网页，避免重复返回整页内容。 */
    @Tool(description = "Read a bounded section of a public web page. Reuse the next offset from a previous "
            + "response, or supply an exact keyword to locate its first occurrence at or after offset. "
            + "Offsets are zero-based Java text character positions. Does not execute JavaScript.")
    public String readWebPage(
            @ToolParam(description = "HTTP or HTTPS URL to read") String url,
            @ToolParam(required = false, description = "Zero-based text offset, default 0") Integer offset,
            @ToolParam(required = false, description = "Maximum text characters, 1-12000; default 8000") Integer limit,
            @ToolParam(required = false, description = "Optional exact, case-sensitive keyword to locate") String keyword) {
        int start = offset == null ? 0 : offset;
        int length = limit == null ? DEFAULT_TEXT_LIMIT : limit;
        if (start < 0 || length < 1 || length > MAX_TEXT_LIMIT) {
            return "网页抓取失败：offset 必须大于等于 0，limit 必须为 1–12000。";
        }
        return fetchPage(url, start, length, keyword);
    }

    /** 共用请求、响应类型识别和错误处理，两个读取入口保持一致。 */
    private String fetchPage(String url, int offset, int limit, String keyword) {
        try {
            String address = WebMediaExtractor.safeUrl("", url);
            if (address.isEmpty()) return "网页抓取失败：请提供有效的 HTTP 或 HTTPS 地址。";
            Connection.Response response = Jsoup.connect(address).timeout(TIMEOUT_MILLIS).maxBodySize(MAX_BODY_BYTES)
                    .ignoreContentType(true).execute();
            String type = response.contentType() == null ? "" : response.contentType().toLowerCase(Locale.ROOT);
            String kind = mediaKind(type, response.url().toString());
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
            return readableContent(response.parse(), offset, limit, keyword);
        } catch (HttpStatusException e) {
            return "网页抓取失败：HTTP " + e.getStatusCode() + "，网站拒绝访问或服务暂时不可用。";
        } catch (Exception e) {
            return "网页抓取失败：连接失败或超时，请检查地址并稍后重试。";
        }
    }

    /** 根据真实响应类型识别媒体；通用二进制类型再使用 URL 后缀辅助识别。 */
    private String mediaKind(String type, String url) {
        if (type.startsWith("image/")) return "图片";
        if (type.startsWith("video/")) return "视频";
        if (type.startsWith("audio/")) return "音频";
        if (type.contains("mpegurl") || type.contains("dash+xml")) return "视频播放列表";
        return type.startsWith("application/octet-stream") ? WebMediaExtractor.kindFromUrl(url) : null;
    }

    /** 保留原有正文提取入口，默认输出首段并附带可继续读取的位置。 */
    String readableContent(Document document) {
        return readableContent(document, 0, DEFAULT_TEXT_LIMIT, null);
    }

    /** 先提取资源，再清理正文；验证页面不提供看似可用的资源结果。 */
    private String readableContent(Document document, int offset, int limit, String keyword) {
        String title = document.title();
        String url = document.location();
        if (title.matches("(?is).*(?:安全验证|验证码|Access Denied|Just a moment).*"))
            return "网页抓取受限：网站返回验证或访问限制页面，当前没有获取到所需资源。\n网页地址：" + url;
        String media = WebMediaExtractor.extract(document);
        String links = pageLinks(document);
        String text = extractText(document);
        if (text.isBlank()) text = media.contains("链接（") ? "页面没有可读正文，已提取页面暴露的媒体链接。"
                : "页面没有可读正文，可能需要客户端加载；当前抓取无法确认页面数据。";
        return "网页标题：" + title.substring(0, Math.min(title.length(), 256)) + "\n网页地址：" + url
                + "\n\n" + textSection(text, offset, limit, keyword) + media + links;
    }

    /** 优先选取 main/article，保留段落换行并删除脚本等不可读内容。 */
    private String extractText(Document document) {
        document.select("script,style,noscript,template,svg").remove();
        Element body = document.selectFirst("main,article,[role=main]");
        if (body == null) body = document.body();
        body.select("p,li,h1,h2,h3,h4,h5,h6,blockquote,pre,tr,section").forEach(block -> block.appendText("\n"));
        return body.wholeText().replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }

    /** 截取正文并报告当前位置；关键词定位保留少量前文，未找到时明确说明。 */
    private String textSection(String text, int offset, int limit, String keyword) {
        if (offset >= text.length()) return "正文读取结束：offset 已达到或超过正文长度 " + text.length() + "。";
        int start = offset;
        if (keyword != null && !keyword.isBlank()) {
            int match = text.indexOf(keyword.strip(), offset);
            if (match < 0) return "未在当前 offset 之后找到指定关键词；可调整关键词或按 offset 继续读取。";
            start = Math.max(offset, match - Math.min(200, limit / 4));
        }
        int end = Math.min(text.length(), start + limit);
        String section = text.substring(start, end);
        if (start == 0 && end == text.length()) return section;
        return section + "\n\n正文范围：" + start + "–" + end + " / " + text.length() + " 字符。"
                + (end < text.length() ? "继续读取请调用 readWebPage，offset=" + end + "。" : "已到正文末尾。");
    }

    /** 收集可继续调查的网页链接，排除媒体、页内锚点和非 HTTP 地址并限制长度。 */
    private String pageLinks(Document document) {
        Map<String, String> links = new LinkedHashMap<>();
        int characters = 0;
        for (Element anchor : document.select("a[href]")) {
            String href = anchor.attr("href").strip();
            if (href.isEmpty() || href.startsWith("#")) continue;
            String url = WebMediaExtractor.safeUrl(document.baseUri(), href);
            if (url.isEmpty() || url.length() > 2048 || WebMediaExtractor.kindFromUrl(url) != null) continue;
            String page = url.split("#", 2)[0];
            if (page.equals(document.location().split("#", 2)[0]) || links.containsKey(page)) continue;
            String title = WebMediaExtractor.linkLabel(anchor.text(), "网页");
            if (characters + page.length() + title.length() > PAGE_LINK_TEXT_LIMIT) continue;
            links.put(page, title);
            characters += page.length() + title.length();
            if (links.size() == PAGE_LINK_LIMIT) break;
        }
        if (links.isEmpty()) return "";
        StringBuilder result = new StringBuilder("\n\n相关网页链接（最多 20 条，可继续抓取）：\n");
        links.forEach((url, title) -> result.append("- [").append(title).append("](").append(url).append(")\n"));
        return result.toString().stripTrailing();
    }
}

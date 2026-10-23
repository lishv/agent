package com.xiaoli.aiagent.tools;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/**
 * 使用 Hutool 抓取 Bing 搜索结果，无需 SearchAPI 密钥。
 */
public class WebSearchScrapingTool {

    private static final String SEARCH_URL = "https://cn.bing.com/search";
    private static final int RESULT_LIMIT = 10;
    private static final int TIMEOUT_MILLIS = 15000;
    private static final int MAX_SNIPPET_LENGTH = 1200;

    /**
     * 搜索 Bing，返回最多 10 个结果，每个标题、链接和摘要。
     * @param query 搜索关键词
     * @return 搜索结果 JSON 字符串
     */
    @Tool(description = "Search Bing and return up to 10 results with title, link and snippet")
    public String searchWebScraping(@ToolParam(description = "Search query keyword") String query) {
        if (query == null || query.isBlank()) {
            return "Error searching Bing: Search query must not be blank";
        }
        // GET 的 form 参数由 Hutool 编码并拼接到 URL，支持中文和特殊字符。
        try (HttpResponse response = HttpRequest.get(SEARCH_URL)
                .form("q", query.trim())
                .form("ensearch", 1)// 国际版
                .form("count", RESULT_LIMIT)
                .timeout(TIMEOUT_MILLIS)
                .execute()) {
            if (!response.isOk()) {
                return "Error searching Bing: HTTP " + response.getStatus();
            }
            String result = parseSearchResults(response.body());
            return "[]".equals(result) ? "未找到匹配的搜索结果，请调整关键词后重试。" : result;
        } catch (Exception e) {
            return "Error searching Bing: " + e.getMessage();
        }
    }
    /**
     * 解析 Bing 搜索结果 HTML，提取普通搜索结果。
     * @param html 搜索结果 HTML
     * @return 搜索结果 JSON 字符串
     */
    String parseSearchResults(String html) {
        Document document = Jsoup.parse(html, SEARCH_URL);
        JSONArray results = JSONUtil.createArray();
        Set<String> seen = new HashSet<>();
        // 仅提取普通搜索结果，跳过广告、相关搜索和页面导航。
        for (Element item : document.select("#b_results > li.b_algo")) {
            Element anchor = item.selectFirst("h2 a[href]");
            if (anchor == null || anchor.text().isBlank()) {
                continue;
            }
            String link = resolveLink(anchor.absUrl("href"));
            if (WebMediaExtractor.safeUrl("", link).isEmpty() || link.length() > 2048 || !seen.add(link)) {
                continue;
            }
            Element snippet = item.selectFirst(".b_caption p");
            if (snippet == null) {
                snippet = item.selectFirst("p");
            }
            String title = anchor.text();
            String summary = snippet == null ? "" : snippet.text();
            results.add(JSONUtil.createObj()
                    .set("title", title.substring(0, Math.min(title.length(), 256)))
                    .set("link", link)
                    .set("snippet", summary.substring(0, Math.min(summary.length(), MAX_SNIPPET_LENGTH))));
            // count 是请求提示，实际返回数量仍需在本地限制。
            if (results.size() == RESULT_LIMIT) {
                break;
            }
        }
        if (results.isEmpty() && document.selectFirst("#b_results .b_no") == null) {
            return "Error searching Bing: No readable search results";
        }
        return results.toString();
    }

    /**
     * 解析 Bing 搜索结果中的跳转链接，返回原始链接。
     * @param link 搜索结果中的跳转链接
     * @return 原始链接
     */
    private String resolveLink(String link) {
        // 部分 Bing 结果使用 /ck/a 跳转链接，u=a1 后的内容是 Base64 URL。
        try {
            URI uri = URI.create(link);
            String host = uri.getHost();
            if (host != null && (host.equals("bing.com") || host.endsWith(".bing.com"))
                    && "/ck/a".equals(uri.getPath()) && uri.getRawQuery() != null) {
                for (String parameter : uri.getRawQuery().split("&")) {
                    if (parameter.startsWith("u=")) {
                        String target = URLDecoder.decode(parameter.substring(2), StandardCharsets.UTF_8);
                        if (target.startsWith("a1")) {
                            target = new String(Base64.getUrlDecoder().decode(target.substring(2)),
                                    StandardCharsets.UTF_8);
                        }
                        if (target.startsWith("https://") || target.startsWith("http://")) {
                            return target;
                        }
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
            // 无法解码时保留可访问的原始链接。
        }
        return link;
    }
}

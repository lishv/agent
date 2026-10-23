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
import java.util.concurrent.TimeUnit;

/**
 * 使用 Hutool 抓取公开搜索结果；普通搜索使用 Bing，深度搜索使用支持分页的 360，无需 API 密钥。
 */
public class WebSearchScrapingTool {

    private static final String SEARCH_URL = "https://cn.bing.com/search";
    private static final String PAGED_SEARCH_URL = "https://www.so.com/s";
    private static final int RESULT_LIMIT = 10;
    private static final int MAX_RESULTS = 50;
    private static final int MAX_PAGES = 10;
    private static final int TIMEOUT_MILLIS = 15000;
    private static final int TOTAL_TIMEOUT_MILLIS = 30000;
    private static final int MAX_SNIPPET_LENGTH = 1200;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    /**
     * 按指定数量搜索，默认返回最多 10 条；超过 10 条时分页抓取并按原始网址去重。
     * @param query 搜索关键词
     * @param maxResults 期望结果数，范围为 1–50；不传时为 10
     * @return 包含标题、链接和摘要的 JSON 数组；未达到数量时同时说明原因
     */
    @Tool(description = "Search public web pages and return relevant titles, URLs, and snippets. "
            + "Use this tool to find information and relevant pages. Use a web scraping tool to read full page content.")
    public String searchWebScraping(
            @ToolParam(description = "Search keywords or query expression") String query,
            @ToolParam(description = "Maximum number of results, 1-50; defaults to 10. Fewer results may be available.", required = false) Integer maxResults) {
        if (query == null || query.isBlank()) {
            return "Error searching Bing: Search query must not be blank";
        }
        int limit = maxResults == null ? RESULT_LIMIT : maxResults;
        if (limit < 1 || limit > MAX_RESULTS) {
            return "Error searching web: maxResults must be 1–50";
        }
        return limit <= RESULT_LIMIT ? searchBing(query.trim(), limit) : searchPages(query.trim(), limit);
    }

    /** 保留原有 Java 调用方式，未指定数量时仍执行默认的 Bing 搜索。 */
    public String searchWebScraping(String query) {
        return searchWebScraping(query, null);
    }

    /** 请求 Bing 首页，并在本地按所需数量截断，避免依赖搜索源是否遵守 count 参数。 */
    private String searchBing(String query, int limit) {
        // GET 的 form 参数由 Hutool 编码并拼接到 URL，支持中文和特殊字符。
        try (HttpResponse response = HttpRequest.get(SEARCH_URL)
                .form("q", query)
                .form("ensearch", 1)// 国际版
                .form("count", limit)
                .timeout(TIMEOUT_MILLIS)
                .execute()) {
            if (!response.isOk()) {
                return "Error searching Bing: HTTP " + response.getStatus();
            }
            String result = parseSearchResults(response.body(), limit);
            return "[]".equals(result) ? "未找到匹配的搜索结果，请调整关键词后重试。" : result;
        } catch (Exception e) {
            return "Error searching Bing: " + e.getMessage();
        }
    }

    /** 使用实测可用的 pn 分页；达到数量、重复页、验证页或请求预算时立即停止。 */
    private String searchPages(String query, int limit) {
        JSONArray results = JSONUtil.createArray();
        Set<String> seen = new HashSet<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_TIMEOUT_MILLIS);
        for (int page = 1; page <= MAX_PAGES; page++) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
                return incompleteResults(results, limit, "搜索已超时或中断");
            }
            try (HttpResponse response = HttpRequest.get(PAGED_SEARCH_URL)
                    .form("q", query)
                    .form("pn", page)
                    .form("rn", limit)
                    .header("User-Agent", USER_AGENT)
                    .timeout((int) Math.min(TIMEOUT_MILLIS, remaining))
                    .setFollowRedirects(true)
                    .execute()) {
                if (!response.isOk()) {
                    return incompleteResults(results, limit, "HTTP " + response.getStatus());
                }
                Document document = Jsoup.parse(response.body(), PAGED_SEARCH_URL);
                var items = document.select("li.res-list[data-result-type=normal], li.res-list[data-result-type=richsum]");
                if (items.isEmpty()) {
                    String text = document.text();
                    String reason = text.contains("验证") ? "搜索源要求安全验证"
                            : text.contains("没有找到") || text.contains("未找到") ? "未找到更多匹配结果"
                            : "页面没有可解析的搜索结果";
                    return incompleteResults(results, limit, reason);
                }
                int before = results.size();
                for (Element item : items) {
                    Element anchor = item.selectFirst("h3.res-title a[href]");
                    if (anchor == null || anchor.text().isBlank()) continue;
                    String link = WebMediaExtractor.safeUrl("", anchor.hasAttr("data-mdurl")
                            ? anchor.attr("data-mdurl") : anchor.absUrl("href"));
                    // 只返回已知原始地址，避免将每页变化的跟踪链接误算成新结果。
                    if (link.isEmpty() || link.length() > 2048 || is360Redirect(link) || !seen.add(link)) continue;
                    Element snippet = item.selectFirst("[data-result-summary], .res-desc");
                    String title = anchor.text();
                    String summary = snippet == null ? "" : snippet.text();
                    results.add(JSONUtil.createObj()
                            .set("title", title.substring(0, Math.min(title.length(), 256)))
                            .set("link", link)
                            .set("snippet", summary.substring(0, Math.min(summary.length(), MAX_SNIPPET_LENGTH))));
                    if (results.size() == limit) return results.toString();
                }
                if (results.size() == before) {
                    return incompleteResults(results, limit, "搜索源返回重复页面或没有新的有效链接");
                }
            } catch (Exception e) {
                return incompleteResults(results, limit, "请求失败：" + e.getMessage());
            }
        }
        return incompleteResults(results, limit, "已达到 " + MAX_PAGES + " 页抓取上限");
    }

    /** 排除尚未解析到目标网址的 360 跳转链接，按实际目标地址去重。 */
    private boolean is360Redirect(String link) {
        URI uri = URI.create(link);
        return ("www.so.com".equalsIgnoreCase(uri.getHost()) || "so.com".equalsIgnoreCase(uri.getHost()))
                && "/link".equals(uri.getPath());
    }

    /** 保留已找到的结果，同时明确实际条数和停止原因，避免把不足数量伪装成成功。 */
    private String incompleteResults(JSONArray results, int limit, String reason) {
        if (results.isEmpty()) return "未找到可用的搜索结果：" + reason + "。";
        return "请求 " + limit + " 条，实际找到 " + results.size() + " 条去重结果；" + reason + "。\n" + results;
    }

    /**
     * 解析 Bing 搜索结果 HTML，提取普通搜索结果。
     * @param html 搜索结果 HTML
     * @return 搜索结果 JSON 字符串
     */
    String parseSearchResults(String html) {
        return parseSearchResults(html, RESULT_LIMIT);
    }

    /** 解析普通 Bing 结果，沿用默认搜索的广告过滤和原始链接解析。 */
    private String parseSearchResults(String html, int limit) {
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
            if (results.size() == limit) {
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

package com.xiaoli.imagesearchmcpserver.tools;

import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Pixabay 图片检索：明确反馈结果、参数错误和限流，不返回空字符串或上游凭据。 */
@Service
public class ImageSearchTool {
    private static final String API_URL = "https://pixabay.com/api/";
    private static final Pattern SITE = Pattern.compile("(?i)(?:^|\\s)site:([^\\s]+)");
    private static final long CACHE_TTL = Duration.ofHours(24).toMillis();
    private final String apiKey;
    private final String apiUrl;
    private final LongSupplier clock;
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();
    private volatile long retryAt;
    private record CachedResult(long expiresAt, String json) { }

    @Autowired
    public ImageSearchTool(@Value("${pixabay.api-key:}") String apiKey) {
        this(apiKey, System::currentTimeMillis);
    }
    ImageSearchTool(String apiKey, LongSupplier clock) {
        this(apiKey, clock, API_URL);
    }
    /** 仅测试时替换上游地址，使用本地 HTTP 服务重现并发等待问题。 */
    ImageSearchTool(String apiKey, LongSupplier clock, String apiUrl) {
        this.apiKey = apiKey; this.clock = clock; this.apiUrl = apiUrl;
    }

    @Tool(description = "Search photos in the Pixabay image library only. Supply short subject keywords, e.g. "
            + "'mountain lake', 'campus landscape' or '校园 风景'. Do not supply a URL or web search operators. "
            + "Returns JSON with status, normalized query, image URLs, download URLs and source pages. "
            + "For no_results, simplify the keywords; for rate_limited, wait retryAfterSeconds and do not repeatedly retry. "
            + "Use scrapeWebPage to inspect media on another website.")
    public String searchImage(@ToolParam(description = "Photo subject keywords, at most 100 characters; "
            + "not a website URL or a site:/filetype: search expression") String query) {
        String keywords;
        try { keywords = normalizeQuery(query); }
        catch (IllegalArgumentException error) { return result("invalid_query", "", error.getMessage()).toString(); }
        if (apiKey == null || apiKey.isBlank())
            return result("not_configured", keywords, "图片搜索服务尚未配置 Pixabay API Key。").toString();
        long now = clock.getAsLong();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        var cached = cache.get(keywords);
        if (cached != null) return JSONUtil.parseObj(cached.json()).set("cached", true).toString();
        if (now < retryAt) return limited(keywords, Math.max(1, (retryAt - now + 999) / 1000)).toString();

        Map<String, Object> params = new HashMap<>();
        params.put("key", apiKey); params.put("q", keywords);
        params.put("image_type", "photo"); params.put("per_page", 10);
        params.put("lang", keywords.matches(".*\\p{IsHan}.*") ? "zh" : "en");
        // 网络等待不占用全局锁；连接与读取预算应留在客户端默认 20 秒之内。
        try (HttpResponse response = HttpUtil.createGet(apiUrl).form(params)
                .setConnectionTimeout(3000).setReadTimeout(10000).execute()) {
            int status = response.getStatus();
            if (status == 429) {
                long seconds = retrySeconds(response);
                retryAt = clock.getAsLong() + seconds * 1000;
                return limited(keywords, seconds).toString();
            }
            if (status != 200) return result("upstream_error", keywords,
                    status == 401 || status == 403 ? "图片服务拒绝访问，请检查 API Key 或服务访问状态。"
                            : "图片服务暂时不可用，请稍后重试。")
                    .set("httpStatus", status).toString();
            JSONObject data;
            try { data = JSONUtil.parseObj(response.body()); }
            catch (RuntimeException error) { return invalidResponse(keywords); }
            JSONArray hits = data.getJSONArray("hits");
            if (hits == null) return invalidResponse(keywords);
            JSONArray images = JSONUtil.createArray();
            for (Object hit : hits) {
                JSONObject item = JSONUtil.parseObj(hit);
                String url = safeUrl(item.getStr("webformatURL"));
                if (url.isEmpty()) continue;
                String download = safeUrl(item.getStr("largeImageURL"));
                images.add(JSONUtil.createObj().set("id", item.get("id"))
                        .set("url", url).set("downloadUrl", download.isEmpty() ? url : download)
                        .set("pageUrl", safeUrl(item.getStr("pageURL"))).set("tags", item.getStr("tags", ""))
                        .set("author", item.getStr("user", ""))
                        .set("width", item.getInt("webformatWidth", 0)).set("height", item.getInt("webformatHeight", 0)));
                if (images.size() == 10) break;
            }
            String json = result(images.isEmpty() ? "no_results" : "ok", keywords,
                    images.isEmpty() ? "没有找到匹配图片，请使用更简短的主题关键词。" : "已找到可用图片，图片地址可交给下载工具。")
                    .set("images", images).set("count", images.size()).set("cached", false).toString();
            if (cache.size() >= 128) cache.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().expiresAt()))
                    .ifPresent(entry -> cache.remove(entry.getKey()));
            cache.put(keywords, new CachedResult(clock.getAsLong() + CACHE_TTL, json));
            return json;
        } catch (Exception error) {
            // 异常文本可能包含完整请求 URL 和 API Key，不能返回给模型或用户。
            return result("network_error", keywords, "图片服务连接失败或超时，请稍后重试。").toString();
        }
    }

    static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("请输入图片主题关键词。");
        var matcher = SITE.matcher(query);
        while (matcher.find()) {
            String host = matcher.group(1).replaceFirst("(?i)^https?://", "").split("/", 2)[0];
            if (!host.equalsIgnoreCase("pixabay.com") && !host.equalsIgnoreCase("www.pixabay.com"))
                throw new IllegalArgumentException("此工具只能搜索 Pixabay 图库；其他站点请使用网页抓取工具。");
        }
        String keywords = matcher.replaceAll(" ").replaceAll("(?i)(?:^|\\s)(?:filetype|ext):\\S+", " ")
                .replace('"', ' ').replaceAll("\\s+", " ").strip();
        if (keywords.matches("(?is).*https?://.*") || keywords.matches("(?is).*(?:intitle|inurl):.*"))
            throw new IllegalArgumentException("请提供图片主题关键词，网址或网页搜索语法请交给网页工具。");
        if (keywords.isBlank()) throw new IllegalArgumentException("去掉网页搜索语法后没有图片关键词，请补充图片主题。");
        if (keywords.codePointCount(0, keywords.length()) > 100)
            throw new IllegalArgumentException("图片关键词最多 100 个字符，请简化描述。");
        return keywords;
    }
    private JSONObject result(String status, String query, String message) {
        return JSONUtil.createObj().set("status", status).set("source", "Pixabay")
                .set("query", query).set("message", message).set("count", 0).set("images", JSONUtil.createArray());
    }
    private JSONObject limited(String query, long seconds) {
        return result("rate_limited", query, "图片服务请求过于频繁，请等待 " + seconds + " 秒后再搜索；不要连续重试。")
                .set("httpStatus", 429).set("retryAfterSeconds", seconds);
    }
    private long retrySeconds(HttpResponse response) {
        for (String header : new String[]{"Retry-After", "X-RateLimit-Reset"}) {
            try {
                long seconds = Long.parseLong(response.header(header));
                if (seconds > 0) return Math.min(seconds, 3600);
            } catch (RuntimeException ignored) { }
        }
        return 60;
    }
    private String invalidResponse(String query) {
        return result("upstream_error", query, "图片服务返回了无法识别的数据，请稍后重试。").toString();
    }
    private static String safeUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && uri.getUserInfo() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    ? uri.toASCIIString() : "";
        } catch (RuntimeException error) { return ""; }
    }
}

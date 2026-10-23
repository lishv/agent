package com.xiaoli.aiagent.tools;

import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.LRUCache;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;

import org.jsoup.Jsoup;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** 复用项目已有的 Bing + Jsoup 检索方式，提取具体图片及来源页，不返回网站目录充数。 */
public class BingImageSearchClient {
    static final String SEARCH_URL = "https://cn.bing.com/images/search";
    private static final int CANDIDATE_LIMIT = 24;
    private static final int TIMEOUT_MILLIS = 10000;
    private static final long RETRY_DELAY_MILLIS = Duration.ofMinutes(1).toMillis();
    private final LRUCache<String, String> cache =
            CacheUtil.newLRUCache(128, Duration.ofHours(24).toMillis());
    private volatile long retryAt;

    /** 读取图片搜索页中的原图候选；搜索引擎的尺寸标签仍由上层用文件头复核。 */
    public JSONArray search(String query, int minWidth, int minHeight) {
        String cacheKey = query + "|" + minWidth + "|" + minHeight;
        String cached = cache.get(cacheKey);
        if (cached != null) return JSONUtil.parseArray(cached);
        if (System.currentTimeMillis() < retryAt) throw new IllegalStateException("图片备用服务正在冷却");
        JSONArray images = fetchCandidates(query, minWidth, minHeight);
        cache.put(cacheKey, images.toString());
        return images;
    }

    /** 将主题和分辨率偏好转换为一次搜索请求，限流时暂停后续未缓存查询。 */
    private JSONArray fetchCandidates(String query, int minWidth, int minHeight) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(
                "q",
                query.isBlank()
                        ? "4K壁纸"
                        : query + (minWidth >= 3840 && minHeight >= 2160 ? " 4K壁纸" : ""));
        params.put("adlt", "strict");
        params.put("first", 1);
        try (HttpResponse response =
                HttpUtil.createGet(SEARCH_URL)
                        .form(params)
                        .header("User-Agent", "Mozilla/5.0 (compatible; Huiling-Image-Search/1.0)")
                        .setFollowRedirects(true)
                        .timeout(TIMEOUT_MILLIS)
                        .execute()) {
            if (response.getStatus() == 429) {
                retryAt = System.currentTimeMillis() + RETRY_DELAY_MILLIS;
                throw new IllegalStateException("图片备用服务请求过于频繁");
            }
            if (!response.isOk()) throw new IllegalStateException("图片备用服务不可用");
            return parseCandidates(response.body(), query);
        }
    }

    /** 提取和去重搜索页暴露的地址；候选格式损坏只影响当前条目。 */
    private JSONArray parseCandidates(String html, String query) {
        JSONArray images = JSONUtil.createArray();
        var document = Jsoup.parse(html, SEARCH_URL);
        var seen = new HashSet<String>();
        for (var element : document.select("a.iusc[m]")) {
            try {
                var image = JSONUtil.parseObj(element.attr("m"));
                String url = WebMediaExtractor.safeUrl("", image.getStr("murl", ""));
                String page = WebMediaExtractor.safeUrl("", image.getStr("purl", ""));
                if (url.isBlank() || page.isBlank() || !seen.add(url)) continue;
                images.add(JSONUtil.createObj()
                        .set("url", url)
                        .set("downloadUrl", url)
                        .set("pageUrl", page)
                        .set("tags", image.getStr("t", query))
                        .set("source", "Bing Images"));
                if (images.size() == CANDIDATE_LIMIT) break;
            } catch (RuntimeException ignored) {
                // 跳过损坏的页面元数据，继续读取其他图片。
            }
        }
        if (images.isEmpty() && document.selectFirst("a.iusc, .imgpt") == null)
            throw new IllegalStateException("图片搜索页没有可读结果");
        return images;
    }
}

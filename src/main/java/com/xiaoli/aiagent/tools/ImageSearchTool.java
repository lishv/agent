package com.xiaoli.aiagent.tools;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** 为图片 MCP 增加备用检索和数量限制，标注实际分辨率，保留可用预览图。 */
public class ImageSearchTool {
    private static final int DEFAULT_COUNT = 3;
    private static final int MAX_COUNT = 10;
    private static final int MAX_DIMENSION = 16384;
    private static final int MAX_CANDIDATES = 24;
    private static final int METADATA_TIMEOUT_MILLIS = 5000;
    private static final int VERIFICATION_BUDGET_MILLIS = 15000;
    private static final int REMOTE_RETRY_DELAY_MILLIS = 60000;
    private static final BingImageSearchClient SHARED_FALLBACK = new BingImageSearchClient();
    private final ToolCallback remote;
    private final BingImageSearchClient fallback;
    private final ImageMetadataReader metadata;
    private final LongSupplier clock;
    private volatile long remoteRetryAt;

    /** 保留已连接的图片 MCP，所有任务共用有容量与有效期限制的备用图库缓存。 */
    public ImageSearchTool(ToolCallback remote) {
        this(remote, SHARED_FALLBACK, new ImageMetadataReader(), System::currentTimeMillis);
    }

    /** 注入时钟与外部读取器，便于离线验证超时、分辨率和失败降级。 */
    ImageSearchTool(
            ToolCallback remote,
            BingImageSearchClient fallback,
            ImageMetadataReader metadata,
            LongSupplier clock) {
        this.remote = remote;
        this.fallback = fallback;
        this.metadata = metadata;
        this.clock = clock;
    }

    /** 按数量返回图片与预览图；检查真实像素并标记是否达到目标分辨率。 */
    @Tool(
            description =
                    "Find actual images with verified image URLs, source pages and actual pixel"
                        + " dimensions. Use short English subject keywords such as 'kitten' or"
                        + " 'mountain lake', and set count to the user's requested number (1-10)."
                        + " For 4K wallpapers use query='4K wallpaper' (or add the requested"
                        + " subject), minWidth=3840, minHeight=2160 as preferred resolution. This"
                        + " tool automatically switches from unavailable Pixabay MCP to another"
                        + " image library. Inspect complete/count/message: never claim missing"
                        + " images were found, never replace images with website homepages."
                        + " downloadUrl is the actual verified image; use downloadResource to save"
                        + " requested files, and preserve source credits. Previews ARE acceptable:"
                        + " return them even below the preferred resolution, and label"
                        + " isPreview/meetsRequestedResolution accurately. Do not label 1280px or"
                        + " 1920px thumbnails as 4K.")
    public String searchImage(
            @ToolParam(
                            description =
                                    "Short image subject, preferably English; not a URL or web"
                                        + " search expression")
                    String query,
            @ToolParam(
                            required = false,
                            description = "Number of distinct images requested, 1-10; default 3")
                    Integer count,
            @ToolParam(
                            required = false,
                            description =
                                    "Preferred original width; use 3840 for landscape 4K. Smaller"
                                        + " previews remain included")
                    Integer minWidth,
            @ToolParam(
                            required = false,
                            description =
                                    "Preferred original height; use 2160 for landscape 4K. Smaller"
                                        + " previews remain included")
                    Integer minHeight) {
        int requested = count == null ? DEFAULT_COUNT : count;
        int width = minWidth == null ? 0 : minWidth;
        int height = minHeight == null ? 0 : minHeight;
        if (!validRequest(query, requested, width, height))
            return JSONUtil.createObj()
                    .set("status", "invalid_query")
                    .set("message", "请输入简短图片主题，数量为 1–10 张，像素下限为 0–16384。")
                    .toString();
        String keywords = query.strip().replaceAll("\\s+", " ");
        if (keywords.matches("(?is).*(?<![a-z0-9])(?:4k|uhd)(?![a-z0-9]).*")) {
            width = Math.max(width, 3840);
            height = Math.max(height, 2160);
        }
        JSONArray found = JSONUtil.createArray();
        Set<String> seen = new HashSet<>();
        long deadline = 0;
        boolean fallbackUsed = false;
        boolean providerFailed = false;
        // 高分辨率需求先检索可能的原图，但后续同样保留能够访问的预览图。
        if (width <= 1280 && height <= 1280 && clock.getAsLong() >= remoteRetryAt) {
            try {
                JSONArray candidates = searchRemote(keywords);
                deadline = clock.getAsLong() + VERIFICATION_BUDGET_MILLIS;
                collect(candidates, "Pixabay", found, seen, requested, width, height, deadline);
            } catch (RuntimeException error) {
                providerFailed = true;
                // 同一次任务内停止反复撞到相同 MCP 超时，之后仍允许自动恢复。
                remoteRetryAt = clock.getAsLong() + REMOTE_RETRY_DELAY_MILLIS;
            }
        }
        if (found.size() < requested) {
            fallbackUsed = true;
            try {
                JSONArray candidates = fallback.search(fallbackKeywords(keywords), width, height);
                if (deadline == 0) deadline = clock.getAsLong() + VERIFICATION_BUDGET_MILLIS;
                collect(candidates, "Bing Images", found, seen, requested, width, height, deadline);
            } catch (RuntimeException error) {
                providerFailed = true;
            }
        }
        return searchResult(keywords, requested, width, height, found, fallbackUsed, providerFailed);
    }

    /** 校验主题、数量和像素偏好，非法请求不调用外部图库。 */
    private boolean validRequest(String query, int count, int width, int height) {
        return query != null && !query.isBlank()
                && query.codePointCount(0, query.length()) <= 100
                && !query.matches("(?is).*https?://.*|.*(?:site|filetype|inurl):.*")
                && count >= 1 && count <= MAX_COUNT
                && width >= 0 && width <= MAX_DIMENSION
                && height >= 0 && height <= MAX_DIMENSION;
    }

    /** 远端图库仅接收关键词；不传递文件工具使用的用户身份和任务上下文。 */
    private JSONArray searchRemote(String keywords) {
        String arguments = JSONUtil.createObj().set("query", keywords).toString();
        JSONObject data = unwrap(remote.call(arguments));
        String status = data.getStr("status");
        if (!"ok".equals(status) && !"no_results".equals(status))
            throw new IllegalStateException("图片 MCP 不可用");
        return data.getJSONArray("images");
    }

    /** 从已验证图片生成统计，来源去重保持检索顺序，不额外维护重复状态。 */
    private String searchResult(String keywords, int requested, int width, int height,
                                JSONArray found, boolean fallbackUsed, boolean providerFailed) {
        boolean complete = found.size() == requested;
        boolean allMeetResolution =
                !found.isEmpty()
                        && found.stream()
                                .map(JSONUtil::parseObj)
                                .allMatch(image -> image.getBool("meetsRequestedResolution", false));
        String status = !found.isEmpty() ? "ok" : providerFailed ? "network_error" : "no_results";
        String sources = found.stream().map(JSONUtil::parseObj).map(image -> image.getStr("source"))
                .distinct().collect(Collectors.joining(" / "));
        String message = complete
                ? "已找到 " + found.size() + " 张不同图片，实际分辨率已标注。"
                    + (allMeetResolution ? "" : "其中包含预览图，可浏览或下载。")
                : "仅找到 " + found.size() + "/" + requested
                    + " 张可用图片，部分图源暂时不可访问或没有匹配结果。";
        return JSONUtil.createObj()
                .set("status", status)
                .set("source", sources)
                .set("query", keywords)
                .set("count", found.size())
                .set("requestedCount", requested)
                .set("complete", complete)
                .set("fallbackUsed", fallbackUsed)
                .set("allMeetRequestedResolution", allMeetResolution)
                .set("minWidth", width)
                .set("minHeight", height)
                .set("images", found)
                .set("message", message)
                .toString();
    }

    /** 排除网页与重复链接，保留真实图片；尺寸不足时标为预览图，不再丢弃。 */
    private void collect(
            JSONArray candidates,
            String source,
            JSONArray found,
            Set<String> seen,
            int count,
            int width,
            int height,
            long deadline) {
        if (candidates == null) return;
        int inspected = 0;
        for (Object raw : candidates) {
            if (found.size() >= count || inspected++ >= MAX_CANDIDATES || clock.getAsLong() >= deadline) break;
            JSONObject image;
            try {
                image = JSONUtil.parseObj(raw);
            } catch (RuntimeException ignored) {
                // 单个损坏条目不应中断其他图片的校验。
                continue;
            }
            String url = WebMediaExtractor.safeUrl("", image.getStr("downloadUrl", image.getStr("url", "")));
            String page = WebMediaExtractor.safeUrl("", image.getStr("pageUrl", ""));
            if (url.isBlank() || page.isBlank() || !seen.add(url)) continue;
            int timeout = (int) Math.max(1, Math.min(METADATA_TIMEOUT_MILLIS, deadline - clock.getAsLong()));
            var dimensions = metadata.read(url, timeout);
            if (dimensions == null) continue;
            boolean meetsResolution = dimensions.width() >= width && dimensions.height() >= height;
            found.add(
                    JSONUtil.createObj()
                            .set("url", url)
                            .set("downloadUrl", url)
                            .set("pageUrl", page)
                            .set("source", source)
                            .set("tags", image.getStr("tags", "图片"))
                            .set("author", image.getStr("author", ""))
                            .set("width", dimensions.width())
                            .set("height", dimensions.height())
                            .set("meetsRequestedResolution", meetsResolution)
                            .set("isPreview", !meetsResolution)
                            .set("resolutionVerified", true));
        }
    }

    /** 兼容 MCP 文本块与直接 JSON 响应，只取真实工具返回，不解析模型生成的说明。 */
    private JSONObject unwrap(String response) {
        String text = response;
        for (int depth = 0; depth < 4; depth++) {
            Object data = JSONUtil.parse(text);
            if (data instanceof JSONObject object && object.containsKey("status")) return object;
            JSONArray blocks =
                    data instanceof JSONArray array
                            ? array
                            : ((JSONObject) data).getJSONArray("content");
            if (blocks == null || blocks.isEmpty()) throw new IllegalArgumentException("无图片结果");
            text = blocks.getJSONObject(0).getStr("text");
        }
        throw new IllegalArgumentException("无可读图片结果");
    }

    /** 壁纸和分辨率属于筛选条件，避免把它们当作必须匹配的图片主题。 */
    private String fallbackKeywords(String query) {
        return query.replaceAll("(?i)(?<![a-z0-9])(?:4k|uhd)(?![a-z0-9])", " ")
                .replaceAll("(?i)\\b(?:wallpapers?|photos?|pictures?|images?)\\b", " ")
                .replace("壁纸", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }
}

package com.xiaoli.aiagent.tools;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 从页面已有标签提取媒体地址，不执行页面脚本。 */
final class WebMediaExtractor {
    private static final int GROUP_LIMIT = 30;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_LINK_CHARACTERS = 16000;
    private static final String EMBEDDED_PAGE = "嵌入页面（非媒体直链）";
    private final Map<String, Map<String, String>> media = new LinkedHashMap<>();
    private int linkCharacters;
    private boolean truncated;

    /** 固定分组与输出顺序，每次解析使用独立的去重集合。 */
    private WebMediaExtractor() {
        for (String kind : new String[]{"图片", "视频", "音频", EMBEDDED_PAGE})
            media.put(kind, new LinkedHashMap<>());
    }

    /** 汇总媒体标签、分享元数据、直接资源链接和嵌入页面。 */
    static String extract(Document document) {
        var extractor = new WebMediaExtractor();
        extractor.extractMediaTags(document);
        extractor.extractMetadata(document);
        extractor.extractLinkedResources(document);
        return extractor.format();
    }

    /** 读取原生媒体标签和常见懒加载属性，保留 srcset 中不同尺寸的地址。 */
    private void extractMediaTags(Document document) {
        for (Element element : document.select("img,picture source,video,audio,video source,audio source")) {
            String kind = elementKind(element);
            String label = element.hasAttr("alt") ? element.attr("alt") : element.attr("title");
            for (String attribute : new String[]{"src", "data-src", "data-original", "data-lazy-src"})
                add(kind, element, element.attr(attribute), label);
            for (String attribute : new String[]{"srcset", "data-srcset"}) {
                String srcset = element.attr(attribute);
                if (srcset.startsWith("data:")) continue;
                for (String candidate : srcset.split(","))
                    add(kind, element, candidate.strip().split("\\s+", 2)[0], label);
            }
            if (kind.equals("视频")) add("图片", element, element.attr("poster"), "视频封面");
        }
    }

    /** source 标签使用父级媒体类型，picture 中的 source 视为图片。 */
    private String elementKind(Element element) {
        String tag = element.tagName();
        if ("source".equals(tag) && element.parent() != null) tag = element.parent().tagName();
        return switch (tag) {
            case "video" -> "视频";
            case "audio" -> "音频";
            default -> "图片";
        };
    }

    /** 提取 Open Graph 与 Twitter 分享标签中的资源地址。 */
    private void extractMetadata(Document document) {
        for (Element meta : document.select("meta[property],meta[name]")) {
            String property = (meta.hasAttr("property") ? meta.attr("property") : meta.attr("name")).toLowerCase(Locale.ROOT);
            String kind = metadataKind(property);
            if (kind != null) add(kind, meta, meta.attr("content"), document.title());
        }
    }

    /** 将常见分享元数据名称转换为媒体类别，其他属性不参与资源提取。 */
    private String metadataKind(String property) {
        if (property.matches("(?:og:image|twitter:image)(?::(?:url|secure_url))?")) return "图片";
        if (property.matches("og:video(?::(?:url|secure_url))?")) return "视频";
        if (property.matches("og:audio(?::(?:url|secure_url))?")) return "音频";
        return null;
    }

    /** 按后缀发现直接媒体链接，播放器 iframe 始终标为嵌入页面。 */
    private void extractLinkedResources(Document document) {
        for (Element anchor : document.select("a[href]")) {
            String kind = kindFromUrl(anchor.absUrl("href"));
            if (kind != null) add(kind, anchor, anchor.attr("href"), anchor.text());
        }
        for (Element frame : document.select("iframe[src]"))
            add(EMBEDDED_PAGE, frame, frame.attr("src"), frame.attr("title"));
    }

    /** 仅按 URL 路径的扩展名推测媒体类别，不据此宣称资源已经下载或验证。 */
    static String kindFromUrl(String url) {
        try {
            String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
            if (path.matches(".*\\.(?:jpg|jpeg|png|gif|webp|avif|bmp|svg)$")) return "图片";
            if (path.matches(".*\\.(?:mp4|webm|mov|m4v|ogv|m3u8|mpd)$")) return "视频";
            if (path.matches(".*\\.(?:mp3|wav|ogg|m4a|aac|flac|opus)$")) return "音频";
        } catch (RuntimeException ignored) {
            // 不合法的地址不作为媒体链接。
        }
        return null;
    }

    /** 解析相对地址，只保留无内嵌凭据的 HTTP(S) URL，并转义 Markdown 结构字符。 */
    static String safeUrl(String base, String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(base).resolve(value.strip().replace(" ", "%20"));
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) return "";
            return uri.normalize().toASCIIString().replace("(", "%28").replace(")", "%29")
                    .replace("<", "%3C").replace(">", "%3E");
        } catch (RuntimeException error) { return ""; }
    }

    /** 生成有界的 Markdown 链接名称，避免页面标签破坏工具输出格式。 */
    static String linkLabel(String label, String defaultLabel) {
        String name = label == null || label.isBlank() ? defaultLabel : label.replaceAll("\\s+", " ").strip();
        return name.substring(0, Math.min(name.length(), 120)).replaceAll("([\\\\`*\\[\\]<>])", "\\\\$1");
    }

    /** 每组去重、限制条数与总字符预算，防止媒体链接占满模型上下文。 */
    private void add(String kind, Element element, String value, String label) {
        String url = safeUrl(element.baseUri(), value);
        if (url.isEmpty()) return;
        Map<String, String> links = media.get(kind);
        if (links.containsKey(url)) return;
        String name = linkLabel(label, kind);
        if (links.size() >= GROUP_LIMIT || url.length() > MAX_URL_LENGTH
                || linkCharacters + url.length() + name.length() > MAX_LINK_CHARACTERS) {
            truncated = true;
            return;
        }
        links.put(url, name);
        linkCharacters += url.length() + name.length();
    }

    /** 输出分组链接；没有资源或达到条数、长度限制时明确提示。 */
    private String format() {
        StringBuilder result = new StringBuilder();
        for (var group : media.entrySet()) {
            if (group.getValue().isEmpty()) continue;
            result.append("\n\n").append(group.getKey()).append("链接（").append(group.getValue().size()).append(" 条）：\n");
            int index = 0;
            for (var link : group.getValue().entrySet())
                result.append(++index).append(". [").append(link.getValue()).append("](").append(link.getKey()).append(")\n");
        }
        if (result.isEmpty()) result.append("\n\n当前页面没有暴露可读取的图片、视频或音频地址；动态加载资源需要对应接口或浏览器能力。");
        if (truncated) result.append("\n链接较多，每类最多展示 30 条；过长链接和超过总长度预算的链接已省略。\n");
        return result.toString().stripTrailing();
    }
}

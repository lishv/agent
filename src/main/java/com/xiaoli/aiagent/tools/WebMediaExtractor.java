package com.xiaoli.aiagent.tools;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 从页面已有标签提取媒体地址，不执行页面脚本。 */
final class WebMediaExtractor {
    private final Map<String, Map<String, String>> media = new LinkedHashMap<>();
    private boolean truncated;

    private WebMediaExtractor() {
        for (String kind : new String[]{"图片", "视频", "音频", "嵌入页面（非媒体直链）"})
            media.put(kind, new LinkedHashMap<>());
    }

    static String extract(Document document) {
        var extractor = new WebMediaExtractor();
        for (Element element : document.select("img,picture source,video,audio,video source,audio source")) {
            String kind = element.tagName().equals("video") || element.parent() != null && element.parent().tagName().equals("video")
                    ? "视频" : element.tagName().equals("audio") || element.parent() != null && element.parent().tagName().equals("audio")
                    ? "音频" : "图片";
            String label = element.hasAttr("alt") ? element.attr("alt") : element.attr("title");
            for (String attribute : new String[]{"src", "data-src", "data-original", "data-lazy-src"})
                extractor.add(kind, element, element.attr(attribute), label);
            for (String attribute : new String[]{"srcset", "data-srcset"}) {
                String srcset = element.attr(attribute);
                if (srcset.startsWith("data:")) continue;
                for (String candidate : srcset.split(","))
                    extractor.add(kind, element, candidate.strip().split("\\s+", 2)[0], label);
            }
            if (kind.equals("视频")) extractor.add("图片", element, element.attr("poster"), "视频封面");
        }
        for (Element meta : document.select("meta[property],meta[name]")) {
            String property = (meta.hasAttr("property") ? meta.attr("property") : meta.attr("name")).toLowerCase(Locale.ROOT);
            String kind = property.matches("(?:og:image|twitter:image)(?::(?:url|secure_url))?") ? "图片"
                    : property.matches("og:video(?::(?:url|secure_url))?") ? "视频"
                    : property.matches("og:audio(?::(?:url|secure_url))?") ? "音频" : null;
            if (kind != null) extractor.add(kind, meta, meta.attr("content"), document.title());
        }
        for (Element anchor : document.select("a[href]")) {
            String kind = kindFromUrl(anchor.absUrl("href"));
            if (kind != null) extractor.add(kind, anchor, anchor.attr("href"), anchor.text());
        }
        for (Element frame : document.select("iframe[src]"))
            extractor.add("嵌入页面（非媒体直链）", frame, frame.attr("src"), frame.attr("title"));
        return extractor.format();
    }

    static String kindFromUrl(String url) {
        try {
            String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
            if (path.matches(".*\\.(?:jpg|jpeg|png|gif|webp|avif|bmp|svg)$")) return "图片";
            if (path.matches(".*\\.(?:mp4|webm|mov|m4v|ogv|m3u8|mpd)$")) return "视频";
            if (path.matches(".*\\.(?:mp3|wav|ogg|m4a|aac|flac|opus)$")) return "音频";
        } catch (RuntimeException ignored) { }
        return null;
    }

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

    private void add(String kind, Element element, String value, String label) {
        String url = safeUrl(element.baseUri(), value);
        if (url.isEmpty()) return;
        Map<String, String> links = media.get(kind);
        if (links.containsKey(url)) return;
        if (links.size() >= 30) { truncated = true; return; }
        String name = label == null || label.isBlank() ? kind : label.replaceAll("\\s+", " ").strip();
        links.put(url, name.substring(0, Math.min(name.length(), 120)).replaceAll("([\\\\`*\\[\\]<>])", "\\\\$1"));
    }

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
        if (truncated) result.append("\n链接较多，每类最多展示 30 条。\n");
        return result.toString().stripTrailing();
    }
}

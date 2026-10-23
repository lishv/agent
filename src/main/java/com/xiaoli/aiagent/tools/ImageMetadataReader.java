package com.xiaoli.aiagent.tools;

import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.core.io.LimitedInputStream;

import java.io.InputStream;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** 只解析图片头，避免为尺寸校验解码整张 4K 图片或写入临时文件。 */
public class ImageMetadataReader {
    private static final int MAX_HEADER_BYTES = 256 * 1024;

    /** 在给定网络超时内读取实际图片尺寸；网页、损坏数据和不支持的格式不计为图片。 */
    public ImageDimensions read(String url, int timeoutMillis) {
        try (HttpResponse response =
                        HttpUtil.createGet(url)
                                .header("User-Agent", "Huiling-Image-Search/1.0")
                                .setFollowRedirects(true)
                                .timeout(timeoutMillis)
                                .executeAsync();
                InputStream body = response.bodyStream()) {
            if (!response.isOk() || body == null) return null;
            return readDimensions(body);
        } catch (Exception error) {
            // 外站异常可能包含带签名的 URL，只将不可用状态交给检索器。
            return null;
        }
    }

    /** 只允许图片读取器探测有限文件头，并释放读取器自身资源。 */
    private ImageDimensions readDimensions(InputStream body) throws java.io.IOException {
        try (var input = new MemoryCacheImageInputStream(new LimitedInputStream(body, MAX_HEADER_BYTES))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return width > 0 && height > 0 ? new ImageDimensions(width, height) : null;
            } finally {
                reader.dispose();
            }
        }
    }
}

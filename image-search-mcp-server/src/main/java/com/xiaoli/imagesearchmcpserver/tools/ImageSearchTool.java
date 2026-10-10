package com.xiaoli.imagesearchmcpserver.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ImageSearchTool {
    // Pixabay的 API URL
    private static final String API_URL = "https://pixabay.com/api/";
    // Pixabay的 API Key
    @Value("${pixabay.api-key:}")
    private String apiKey;

    @Tool(description = "search image from web")
    public String searchImage(@ToolParam(description = "Search query keyword") String query) {
        try {
            // 搜索图片
            return String.join(",", searchMediumImages(query));
        } catch (Exception e) {
            // 搜索图片失败
            return "Error search image: " + e.getMessage();
        }
    }

    public List<String> searchMediumImages(String query) {
        if (StrUtil.isBlank(apiKey)) {
            // 没有配置 API Key
            throw new IllegalStateException("Pixabay API key is not configured");
        }

        Map<String, Object> params = new HashMap<>();
        // 构建请求参数
        params.put("key", apiKey);
        params.put("q", query);
        params.put("image_type", "photo");
        // 发送请求
        String response = HttpUtil.createGet(API_URL)
                .form(params)
                .execute()
                .body();
        // 解析响应
        return JSONUtil.parseObj(response)
                .getJSONArray("hits")
                .stream()
                .map(hitObj -> (JSONObject) hitObj)
                .map(hit -> hit.getStr("webformatURL"))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }
}
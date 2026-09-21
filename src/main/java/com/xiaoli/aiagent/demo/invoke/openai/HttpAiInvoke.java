package com.xiaoli.aiagent.demo.invoke.openai;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xiaoli.aiagent.demo.invoke.TestApiKey;

/**
 * HTTP 方式调用 AI（OpenAI 兼容模式）
 */
public class HttpAiInvoke {

    public static void main(String[] args) {
        // API密钥
        String apiKey = TestApiKey.API_KEY;

        // 构建请求URL
        String url = "https://llm-qsrjk2ppulkkmafg.cn-beijing.maas.aliyuncs.com"
                + "/compatible-mode/v1/chat/completions";

        // 添加系统消息
        JSONObject systemMessage = new JSONObject();
        systemMessage.set("role", "system");
        systemMessage.set("content", "You are a helpful assistant.");

        // 添加用户消息
        JSONObject userMessage = new JSONObject();
        userMessage.set("role", "user");
        userMessage.set("content", "你是谁？");

        // 组装 messages 数组
        JSONObject requestJson = new JSONObject();
        requestJson.set("model", "qwen3.8-max");
        requestJson.set("messages", JSONUtil.createArray()
                .set(systemMessage)
                .set(userMessage));

        // 发送请求
        String result = HttpRequest.post(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestJson.toString())
                .execute()
                .body();

        // 输出结果
        System.out.println(result);
    }
}
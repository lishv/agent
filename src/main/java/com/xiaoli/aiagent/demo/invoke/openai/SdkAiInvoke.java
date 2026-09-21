package com.xiaoli.aiagent.demo.invoke.openai;// 该代码 OpenAI SDK 版本为 2.6.0
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.xiaoli.aiagent.demo.invoke.TestApiKey;

public class SdkAiInvoke {
    private static ChatCompletion getChatCompletion() {
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(TestApiKey.API_KEY)
                .baseUrl("https://llm-qsrjk2ppulkkmafg.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")
                .build();

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .addUserMessage("你是谁")
                .model("qwen3.8-max")
                .build();
        ChatCompletion chatCompletion = client.chat().completions().create(params);
        return chatCompletion;
    }
    public static void main(String[] args) {

        try {
            ChatCompletion chatCompletion = getChatCompletion();
            System.out.println(chatCompletion);
        } catch (Exception e) {
            System.err.println("Error occurred: " + e.getMessage());
            e.printStackTrace();
        }
    }


}
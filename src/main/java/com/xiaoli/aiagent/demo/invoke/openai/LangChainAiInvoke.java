package com.xiaoli.aiagent.demo.invoke.openai;


import dev.langchain4j.model.openai.OpenAiChatModel;

public class LangChainAiInvoke {


    public static void main(String[] args) {
        String apiKey = "sk-wQS5r2y0bYnuqTLQWPGXJHUAzwYRIEkiPDhpAKiqtRiXuTXF";
        String modelName = "gpt-5.6-terra";
        String baseUrl = "https://cheapai.info/v1";
        OpenAiChatModel openAiChatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
        String answer = openAiChatModel.chat("我是程序员鱼皮，这是编程导航 codefather.cn 的 AI 超级智能体原创项目");
        System.out.println(answer);
    }
}

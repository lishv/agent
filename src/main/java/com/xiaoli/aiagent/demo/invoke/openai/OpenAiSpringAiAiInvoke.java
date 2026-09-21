package com.xiaoli.aiagent.demo.invoke.openai;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;


/**
 * Spring AI 框架调用 AI 大模型（OpenAI）
 */
// 取消注释后，项目启动时会执行
//@Component
public class OpenAiSpringAiAiInvoke implements CommandLineRunner {

    @Resource
    private ChatModel openAiChatModel;

    @Override
    public void run(String... args) throws Exception {
        // 1. 先接收整个 ChatResponse
        ChatResponse response = openAiChatModel.call(new Prompt("你好，我是鱼皮"));
        // 2. 从元数据中提取模型名
        String modelName = response.getMetadata().getModel();
        // 3. 再拿 AssistantMessage 输出内容
        AssistantMessage assistantMessage = response.getResult().getOutput();
        System.out.println("模型名称: " + modelName + "，回复内容: " + assistantMessage.getText());
    }
}

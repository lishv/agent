package com.xiaoli.aiagent.agent;

import com.xiaoli.aiagent.advisor.MyLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * AI 超级智能体（拥有自主规划能力，可以直接使用）
 *  配置模型、工具、提示词、最大步骤数
 */
@Component
public class MyManus extends ToolCallAgent {
// 构造方法初始化,自动合并所有工具,包括内置工具和mcp工具
    public MyManus(ToolCallback[] allTools, @Qualifier("openAiChatModel") ChatModel openAiChatModel, ToolCallbackProvider toolCallbackProvider) {
        //super(allTools);// 调用父类的构造方法,将所有工具传递给父类,用于调用工具函数,仅支持内置的工具
        super(Stream.concat(
                Arrays.stream(allTools),
                Arrays.stream(toolCallbackProvider.getToolCallbacks())
        ).toArray(ToolCallback[]::new));//支持mcp和内置工具的调用
        this.setName("MyManus");
        String SYSTEM_PROMPT = """
                You are MyManus, an all-capable AI assistant, aimed at solving any task presented by the user.
                You have various tools at your disposal that you can call upon to efficiently complete complex requests.
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);
        String NEXT_STEP_PROMPT = """
                Based on user needs, proactively select the most appropriate tool or combination of tools.
                For complex tasks, you can break down the problem and use different tools step by step to solve it.
                After using each tool, clearly explain the execution results and suggest the next steps.
                If you want to stop the interaction at any point, use the `terminate` tool/function call.
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(20);


        // 初始化 AI 对话客户端
        ChatClient chatClient = ChatClient.builder(openAiChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }

}

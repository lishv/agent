package com.xiaoli.aiagent.agent;

import com.xiaoli.aiagent.advisor.MyLoggerAdvisor;
import com.xiaoli.aiagent.tools.AskHumanTool;
import com.xiaoli.aiagent.tools.AiToolCallbacks;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * AI 超级智能体（拥有自主规划能力，可以直接使用）
 *  配置模型、工具、提示词、最大步骤数
 */
@Component
public class MyManus extends ToolCallAgent {
// 构造方法初始化,自动合并所有工具,包括内置工具和mcp工具

    /**
     * 构造方法初始化,自动合并所有工具,包括内置工具和mcp工具
     * @param allTools              所有工具,包括内置工具和mcp工具
     * @param openAiChatModel       聊天模型,用于生成回答
     * @param toolCallbackProvider  工具回调提供方,用于提供工具回调函数
     */
    @Autowired
    public MyManus(ToolCallback[] allTools, @Qualifier("openAiChatModel") ChatModel openAiChatModel, ToolCallbackProvider toolCallbackProvider) {
        this(allTools, openAiChatModel, toolCallbackProvider, null);
    }

    /**
     * 构造方法初始化,自动合并所有工具,包括内置工具和mcp工具
     * @param allTools
     * @param openAiChatModel
     * @param toolCallbackProvider
     * @param humanAnswerProvider  如果提供了向用户询问,用于获取用户回答,避免不同用户的回答互相串用
     */
    public MyManus(ToolCallback[] allTools, ChatModel openAiChatModel,
                   ToolCallbackProvider toolCallbackProvider, Function<String, String> humanAnswerProvider) {
        //super(allTools);// 调用父类的构造方法,将所有工具和MCP工具传递给父类,用于调用工具函数,仅支持内置的工具
        super(Stream.concat(
                //1. 合并所有工具和MCP工具,并过滤掉askHuman工具
                Arrays.stream(AiToolCallbacks.merge(allTools, toolCallbackProvider.getToolCallbacks()))
                        .filter(tool -> !tool.getToolDefinition().name().equals("askHuman")),
                //2. 如果提供了向用户询问,则添加askHuman工具, 如果没有人类回答提供者 → 返回空 Stream（啥也不加）
                humanAnswerProvider == null ? Stream.empty(): Arrays.stream(ToolCallbacks.from(new AskHumanTool(humanAnswerProvider)))
        ).toArray(ToolCallback[]::new));
        this.setName("MyManus");

        String SYSTEM_PROMPT = """
                You are MyManus, a general-purpose AI assistant.
                Use available tools when needed, following their descriptions and parameter requirements.
                When essential information is missing, use askHuman if available and wait for the user's actual answer.
                Base your response on available information and actual tool results.
                Never fabricate actions, results, user answers, or links.
                Provide a useful final answer addressing the user's request, explaining any limitations or incomplete work.
                """;

        this.setSystemPrompt(SYSTEM_PROMPT);

        String NEXT_STEP_PROMPT = """
                Based on user needs, proactively select the most appropriate tool or combination of tools.
                For complex tasks, you can break down the problem and use different tools step by step to solve it.
                After using each tool, clearly explain the execution results and suggest the next steps.
                When the task is complete or cannot proceed, provide the final answer in your response text
                and call `doTerminate`. Do not end with only a completion notice or an empty response.
                """;

        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(20);


        // 初始化 AI 对话客户端
        ChatClient chatClient = ChatClient.builder(openAiChatModel)
                .defaultSystem(getSystemPrompt())
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);//传递给父类,用于生成回答

    }

}

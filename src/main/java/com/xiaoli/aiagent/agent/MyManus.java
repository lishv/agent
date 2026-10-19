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
    @Autowired
    public MyManus(ToolCallback[] allTools, @Qualifier("openAiChatModel") ChatModel openAiChatModel, ToolCallbackProvider toolCallbackProvider) {
        this(allTools, openAiChatModel, toolCallbackProvider, null);
    }

    /** 每个流式任务绑定自己的回答提供方，避免不同用户的回答互相串用。 */
    public MyManus(ToolCallback[] allTools, ChatModel openAiChatModel,
                   ToolCallbackProvider toolCallbackProvider, Function<String, String> humanAnswerProvider) {
        //super(allTools);// 调用父类的构造方法,将所有工具传递给父类,用于调用工具函数,仅支持内置的工具
        super(Stream.concat(
                Arrays.stream(AiToolCallbacks.merge(allTools, toolCallbackProvider.getToolCallbacks()))
                        .filter(tool -> !tool.getToolDefinition().name().equals("askHuman")),
                humanAnswerProvider == null ? Stream.empty()
                        : Arrays.stream(ToolCallbacks.from(new AskHumanTool(humanAnswerProvider)))
        ).toArray(ToolCallback[]::new));
        this.setName("MyManus");
        String SYSTEM_PROMPT = """
                You are MyManus, an all-capable AI assistant, aimed at solving any task presented by the user.
                You have various tools at your disposal that you can call upon to efficiently complete complex requests.
                When essential task information is missing, use askHuman if it is available.
                Wait for its real answer and continue the same task using that answer.
                Never substitute example preferences or fabricate a user response.
                Always provide a useful final answer to the user's request, grounded in actual tool results.
                A tool result or a termination notice is not a final answer. Explain limitations honestly.
                For generated or downloaded files, copy the exact Markdown download link returned by the tool.
                Never invent sandbox:, file:, or local filesystem download URLs. Links expire after the configured retention period (24 hours by default).
                For image-search requests, call searchImage and supply count equal to the requested number.
                Prefer short English subject keywords, e.g. kitten; do not use web-search prose or site: operators.
                For 4K landscape wallpapers set minWidth=3840 and minHeight=2160; without a specified theme,
                query="4K wallpaper" is enough. Return distinct actual images with their verified dimensions,
                source pages and usable image/download links. Website homepages are not image results.
                searchImage already handles provider fallback; do not repeatedly retry an MCP timeout with synonyms.
                If complete=false, state the actual count; never claim that small previews are 4K.
                Preview images are acceptable. Include them rather than discarding them for insufficient resolution,
                and clearly distinguish previews from images that meet the requested original resolution.
                When files are requested, download the returned downloadUrl values and reuse their exact file links.
                Do not call doTerminate before checking the requested image count and resolution against actual results.
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
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }

}

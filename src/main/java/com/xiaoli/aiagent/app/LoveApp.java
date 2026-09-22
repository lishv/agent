package com.xiaoli.aiagent.app;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;


@Component
@Slf4j
public class LoveApp {
//    @Resource
//    private ChatModel dashScopeChatModel;
//    @Resource
//    private ChatModel openAiChatModel;
//    @Resource
//    private ChatModel ollamaChatModel;

    private ChatClient chatClient;
    private static final String SYSTEM_PROMPT = "扮演深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。" +
            "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；" +
            "恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。" +
            "引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。";
    public LoveApp(ChatModel dashScopeChatModel) {
        // 构建基于内存的对话记忆，最多保留 20 条消息
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(20)
                .chatMemoryRepository(new InMemoryChatMemoryRepository()) // 使用内存存储对话历史
                .build();
        // 构建 ChatClient,在用户调用时需要 .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)) 来指定对话 ID
        chatClient = ChatClient.builder(dashScopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()
                        // 自定义日志 Advisor，可按需开启
                        //   new MyLoggerAdvisor(),
                        // 自定义推理增强 Advisor，可按需开启
                        //  new ReReadingAdvisor()
                ).build();
    }


    /**
     * AI 基础对话（支持多轮对话记忆）
     * @param message 用户输入的消息
     * @param chatId  对话 ID，用于保持对话记忆
     * @return AI 回复的消息
     */
    public String doChat(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String model = chatResponse.getMetadata().getModel();
        int promptTokens = chatResponse.getMetadata().getUsage().getPromptTokens();
        int completionTokens = chatResponse.getMetadata().getUsage().getCompletionTokens();
        int totalTokens = chatResponse.getMetadata().getUsage().getTotalTokens();
        log.info("模型:{}:输入Tokens: {}, 输出Tokens: {}, 总Tokens: {}", model, promptTokens, completionTokens, totalTokens);

        String content = chatResponse.getResult().getOutput().getText();
        log.info("模型:{}:content: {}", model, content);

        return content;
    }

}

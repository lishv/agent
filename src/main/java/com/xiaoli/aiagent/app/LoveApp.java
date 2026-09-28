package com.xiaoli.aiagent.app;

import com.xiaoli.aiagent.advisor.MyLoggerAdvisor;
import com.xiaoli.aiagent.chatmemory.FileBasedChatMemoryRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
@Slf4j
public class LoveApp {
//    @Resource
//    private ChatModel dashScopeChatModel;
//    @Resource
//    private ChatModel openAiChatModel;
//    @Resource
//    private ChatModel ollamaChatModel;
    @Resource
    private Advisor loveAppRagCloudAdvisor;

    @Resource
    private VectorStore loveVectorStore;

    private ChatClient chatClient;
    private static final String SYSTEM_PROMPT = "你是深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。" +
            "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；" +
            "恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。" +
            "引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。";

    public LoveApp(ChatModel dashScopeChatModel) {
        //基于初始化文件的对话记忆
        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";

        // 构建基于内存的对话记忆，最多保留 20 条消息
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(20)
                //.chatMemoryRepository(new InMemoryChatMemoryRepository()) // 使用内存存储对话历史
                .chatMemoryRepository(new FileBasedChatMemoryRepository(fileDir)) // 构建基于文件的对话记忆，最多保留 20 条消息
                .build();

        // 构建 ChatClient,在用户调用时需要 .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)) 来指定对话 ID
        chatClient = ChatClient.builder(dashScopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        // 自定义日志 Advisor，可按需开启
                        new MyLoggerAdvisor()
                        // 自定义推理增强 Advisor，可按需开启
                        //new ReReadingAdvisor()
                ).build();
    }


    /**
     * AI 基础对话（支持多轮对话记忆）
     *
     * @param message 用户输入的消息
     * @param chatId  对话 ID，用于保持对话记忆
     * @return AI 回复的消息
     */
    public String doChat(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                //用它标识“这条消息属于哪个会话”： chatId 加载历史消息，并在模型回复后保存本轮对话。因此，同一个 chatId 可以延续上下文
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String model = chatResponse.getMetadata().getModel();
        int promptTokens = chatResponse.getMetadata().getUsage().getPromptTokens();
        int completionTokens = chatResponse.getMetadata().getUsage().getCompletionTokens();
        int totalTokens = chatResponse.getMetadata().getUsage().getTotalTokens();
       log.info("模型:{}:输入Tokens: {}, 输出Tokens: {}, 总Tokens: {}", model, promptTokens, completionTokens, totalTokens);

        String content = chatResponse.getResult().getOutput().getText();
       // log.info("模型:{}:content: {}", model, content);

        return content;
    }

    /**
     * 多模态对话。mediaList 为空时按普通文本对话处理。
     *
     * @param message   用户文本
     * @param chatId    对话 ID
     * @param mediaList 媒体列表，可以为 null 或空列表
     * @return AI 回复
     */
    public String doChatWithMedia(String message, String chatId, List<Media> mediaList) {
        if (message == null || message.isBlank())
            throw new IllegalArgumentException("message 不能为空");
        if (chatId == null || chatId.isBlank())
            throw new IllegalArgumentException("chatId 不能为空");

        ChatResponse chatResponse = chatClient
                .prompt()
                .user(user -> {
                    user.text(message);
                    if (mediaList != null) {
                        mediaList.stream()
                                .filter(media -> media != null)
                                .forEach(media -> user.media(media));
                    }
                })
//                .options(// 限制最大输出令牌数
//                        ChatOptions.builder()
//                                .maxTokens(1024)
//                                .build()
//                )
                // //用它标识“这条消息属于哪个会话”： chatId 加载历史消息，并在模型回复后保存本轮对话。因此，同一个 chatId 可以延续上下文
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();

        if (chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null)
            throw new IllegalStateException("模型未返回有效回复");

        String content = chatResponse.getResult().getOutput().getText();
        String model = chatResponse.getMetadata().getModel();
        int promptTokens = chatResponse.getMetadata().getUsage().getPromptTokens();
        int completionTokens = chatResponse.getMetadata().getUsage().getCompletionTokens();
        int totalTokens = chatResponse.getMetadata().getUsage().getTotalTokens();
        log.info("模型:{}:输入Tokens: {}, 输出Tokens: {}, 总Tokens: {}", model, promptTokens, completionTokens, totalTokens);
        //log.info("多模态对话完成，chatId={}, mediaCount={}", chatId, mediaList == null ? 0 : mediaList.size());
        return content;
    }


    //定义恋爱报告的对象，包含标题和建议列表
    record LoveReport(String title, List<String> suggestions) {
    }

    /**
     * AI 恋爱报告功能（实战结构化输出）
     *
     * @param message
     * @param chatId
     * @return
     */
    public LoveReport doChatWithReport(String message, String chatId) {
        LoveReport loveReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成恋爱结果，标题为{用户名}的恋爱报告，内容为建议列表")
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .entity(LoveReport.class);
        log.info("loveReport: {}", loveReport);
        return loveReport;
    }

    /**
     * 和 RAG 知识库进行对话
     *
     * @param message       用户文本
     * @param chatId        对话 ID
     * @return              AI 回复
     */
    public String doChatWithRag(String message, String chatId) {
//        // 查询重写
//        String rewrittenMessage = queryRewriter.doQueryRewrite(message);
        ChatResponse chatResponse = chatClient
                .prompt()
                // 使用改写后的查询
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果,默认开启过的
                // 应用 RAG 知识库问答
//                .advisors(QuestionAnswerAdvisor
//                        .builder(loveVectorStore)
//                        .build())
                // 应用 RAG 检索增强服务（基于云知识库服务）
                .advisors(loveAppRagCloudAdvisor)
                // 应用 RAG 检索增强服务（基于 PgVector 向量存储）
//                .advisors(new QuestionAnswerAdvisor(pgVectorVectorStore))
                // 应用自定义的 RAG 检索增强服务（文档查询器 + 上下文增强器）
//                .advisors(
//                        LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(
//                                loveAppVectorStore, "单身"
//                        )
//                )
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

}

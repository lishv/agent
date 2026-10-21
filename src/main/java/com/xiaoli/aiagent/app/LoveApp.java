package com.xiaoli.aiagent.app;

import com.xiaoli.aiagent.advisor.MyLoggerAdvisor;
import com.xiaoli.aiagent.advisor.CompactingMemoryAdvisor;
import com.xiaoli.aiagent.app.prompt.LoveAppPrompts;
import com.xiaoli.aiagent.service.ContextMemoryService;
import com.xiaoli.aiagent.exception.ErrorCode;
import com.xiaoli.aiagent.exception.ThrowUtils;
import com.xiaoli.aiagent.rag.LoveAppRagCustomAdvisorFactory;
import com.xiaoli.aiagent.rag.QueryRewriter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import com.xiaoli.aiagent.tools.AiToolCallbacks;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Arrays;
import java.util.Map;


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
    private QueryRewriter queryRewriter;

//    @Resource
//    private VectorStore loveVectorStore;
    @Resource(name = "pgVectorVectorStore")
    private VectorStore pgVectorVectorStore;

    private ChatClient chatClient;
    private static final String SYSTEM_PROMPT = LoveAppPrompts.SYSTEM;

    public LoveApp(@Qualifier("openAiChatModel") ChatModel openAiChatModel, ContextMemoryService contextMemory) {

        contextMemory.registerProfile("love", SYSTEM_PROMPT, new ToolCallback[0], "");

        // 构建 ChatClient,在用户调用时需要 .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)) 来指定对话 ID
        chatClient = ChatClient.builder(openAiChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        new CompactingMemoryAdvisor(contextMemory), //压缩记忆 Advisor，用于压缩上下文记忆，避免内存泄漏。
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
        ThrowUtils.throwIf(message == null || message.isBlank(), ErrorCode.PARAMS_ERROR, "message 不能为空");
        ThrowUtils.throwIf(chatId == null || chatId.isBlank(), ErrorCode.PARAMS_ERROR, "chatId 不能为空");

        ChatResponse chatResponse = chatClient
                .prompt()// 构建提示
                .user(user -> {
                    user.text(message);
                    if (mediaList != null) {
                        mediaList.stream()
                                .filter(media -> media != null)
                                .forEach(media -> user.media(media));
                    }
                })// 添加用户消息,每个消息可以包含文本和媒体
//                .options(// 限制最大输出令牌数
//                        ChatOptions.builder()
//                                .maxTokens(1024)
//                                .build()
//                )
                // //用它标识“这条消息属于哪个会话”： chatId 加载历史消息，并在模型回复后保存本轮对话。因此，同一个 chatId 可以延续上下文
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();

        ThrowUtils.throwIf(chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null,
                ErrorCode.OPERATION_ERROR, "模型未返回有效回复");

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
        // 查询重写
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);
        ChatResponse chatResponse = chatClient
                .prompt()
                // 使用改写后的查询
                .user(rewrittenMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果,默认开启过的
                // 应用 RAG 知识库问答(基于内存)
              //  .advisors(QuestionAnswerAdvisor.builder(loveVectorStore).build())
                // 应用 RAG 检索增强服务（基于云知识库服务）
//                .advisors(loveAppRagCloudAdvisor)
                // 应用 RAG 检索增强服务（基于 PgVector 向量存储）
//                .advisors(QuestionAnswerAdvisor.builder(pgVectorVectorStore).build())
                // 应用自定义的 RAG 检索增强服务（文档查询器 + 上下文增强器）
//                .advisors(LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(pgVectorVectorStore, "单身"))
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
    // AI 调用工具能力
    @Resource
    private ToolCallback[] allTools;

    /**
     * AI 恋爱报告功能（支持调用工具）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithTools(String message, String chatId) {
        return doChatWithTools(message, chatId, Map.of());
    }

    /** 同步工具调用同样接收服务端身份上下文，受保护的文件工具不依赖 HTTP 线程或模型参数。 */
    public String doChatWithTools(String message, String chatId, Map<String, Object> toolContext) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(allTools)
                .toolContext(toolContext)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
    //ai调用mcp工具
    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    /**
     * AI 恋爱报告功能（调用 MCP 服务）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithMcp(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(AiToolCallbacks.merge(new ToolCallback[0], toolCallbackProvider.getToolCallbacks()))
                .call()// 调用模型
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
    /**
     * AI 基础对话（支持多轮对话记忆，SSE 流式传输）
     *目前没用
     * @param message 用户文本
     * @param chatId  对话 ID
     * @return  流式传输的 AI 回复内容
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return doChatByStream(message, chatId, Map.of());
    }

    /**
     * 使用服务端提供的用户和任务上下文执行流式对话，使文件工具能够校验归属并生成下载链接。
     *本页代码除了这个方法和构造方法，其他方法都是暂未实现。
     * @param message 用户消息
     * @param chatId 模型记忆标识
     * @param toolContext 由业务服务注入的工具上下文，不作为模型工具参数
     * @return 流式回复
     */

    public Flux<String> doChatByStream(String message, String chatId, Map<String, Object> toolContext) {
        ToolCallback[] mcpTools = toolCallbackProvider.getToolCallbacks();
        log.debug("恋爱助手可用 MCP 工具：{}", Arrays.stream(mcpTools)
                .map(tool -> tool.getToolDefinition().name()).toList());
        ToolCallback[] tools = AiToolCallbacks.merge(allTools, mcpTools);//合并所有工具+mcp工具
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(QuestionAnswerAdvisor.builder(pgVectorVectorStore)
                        .promptTemplate(new PromptTemplate(LoveAppPrompts.RAG_CONTEXT))
                        .build())// 情感知识作为参考，同时允许地图等工具提供实时事实
                .toolCallbacks(tools)// 包含所有工具+mcp工具
                .toolContext(toolContext)// Spring AI 在后台执行工具时传递本轮用户和任务身份
                .stream()// 开启流式传输
                .content();
    }

}

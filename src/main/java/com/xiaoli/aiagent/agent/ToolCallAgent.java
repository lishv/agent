package com.xiaoli.aiagent.agent;
import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.exception.ErrorCode;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.xiaoli.aiagent.agent.model.AgentState;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;

import java.util.List;
import java.util.ArrayList;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import com.xiaoli.aiagent.service.ContextMemoryService;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 */
@EqualsAndHashCode(callSuper = true)// 调用父类的equals方法
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {
    private static final String FINAL_ANSWER_PROMPT = """
            Tool execution has ended. Now give the user a complete final answer to their original request,
            using the actual tool results and any real answers provided by the user in this conversation.
            Respond in the user's language. Include the useful findings, relevant sources or generated files,
            and clearly explain missing information or failures when the results are insufficient.
            Do not just say the task is finished. Do not fabricate results or claim actions that were not done.
            Do not call any tools or ask for another tool execution. Return the final answer as ordinary text.
            """;

    // 可用的工具
    private final ToolCallback[] availableTools;

    // 保存当前大模型调用的响应,用于从think传递到act方法
    private ChatResponse currentModelResponse;

    // 工具调用管理者
    private final ToolCallingManager toolCallingManager;

    // 参数配置
    private final ChatOptions chatOptions;

    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        var defaultExceptionProcessor = new DefaultToolExecutionExceptionProcessor(false);
        this.toolCallingManager = ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(error -> {
                    // 提问失败没有真实回答，应结束本次任务；其他工具仍沿用框架处理方式。
                    if (error.getToolDefinition().name().equals("askHuman")) {
                        if (error.getCause() instanceof RuntimeException cause) throw cause;
                        throw error;
                    }
                    return defaultExceptionProcessor.process(error);
                }).build();

        // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
//        this.chatOptions = DashScopeChatOptions.builder() //若非阿里的大模型，需要别的
//                .multiModel(true)// 开启多模型支持
                    // think()和act() 手动执行工具时，也能从选项中找到对应工具
//                .toolCallbacks(availableTools)
//                .withInternalToolExecutionEnabled(false)// 由于禁用 Spring AI 内置的工具调用机制,需要在application-local.yml中配置multi-model: true,并开启多模型支持
//                .build();
        this.chatOptions = OpenAiChatOptions.builder()
                //think() act() 手动执行工具时，也能从选项中找到对应工具,全局赋予工具能力
                .toolCallbacks(availableTools)
                // 由自己的 think() / act() 控制工具调用流程
                .internalToolExecutionEnabled(false)
                .build();
    }

    /** 将服务端身份传给工具执行器；这些上下文不作为工具参数暴露给模型。 */
    public void setToolContext(Map<String, Object> context) {
        ((OpenAiChatOptions) chatOptions).setToolContext(Map.copyOf(context));
    }

    /**
     * 处理当前状态并决定下一步是否需要进行工具调用
     *
     * @return 是否需要进行工具调用,获取工具调用决定
     */
    @Override
    public boolean think() {
        // 每次模型调用只添加一次控制提示；在工具步骤完成的边界压缩上下文。
        getMessageList().removeIf(message -> message instanceof UserMessage && getNextStepPrompt() != null
                && getNextStepPrompt().equals(message.getText()));
        setMessageList(new ArrayList<>(getContextPreparer().apply(getMessageList())));
        if (getState() == AgentState.CANCELLED || Thread.currentThread().isInterrupted()) throw new CancellationException("任务已停止");
        // 1、校验提示词，拼接下一步提示词,每轮 think() 都给模型一次当前步骤的引导提醒，防止模型"跑偏"：
        if (StrUtil.isNotBlank(getNextStepPrompt())) {
            // 将下一步提示词转化为UserMessage,防止模型"跑偏",添加到消息上下文
            UserMessage nextStepPrompt = UserMessage.builder().text(getNextStepPrompt())
                    .metadata(Map.of(ContextMemoryService.INTERNAL, true)).build();
            getMessageList().add(nextStepPrompt);
        }
        /*
                        messageList 消息来源统计：
        ┌──────────────────┬───────────────────────────────┐
        │ 来源             │ 什么时候加                     │
        ├──────────────────┼───────────────────────────────┤
        │ run() 开头       │ +1 UserMessage（用户提问）      │
        │ 每轮 think() 开头 │ +1 nextStepPrompt             │
        │ 每轮 act() 结束   │ +1 AssistantMessage(模型回答)  │
        │                  │ +1 ToolResponseMessage        │
        └──────────────────┴───────────────────────────────┘
        */


        List<Message> messageList = getMessageList();//获取当前会话上下文包含
        // 2、调用 AI 大模型，获取本次调用结果
        Prompt prompt = new Prompt(messageList, this.chatOptions);//将历史提示词和调用模型的参数整合到Prompt中
        try {
            // 调用大模型，获取本次调用的响应
            ChatResponse chatResponse = getChatClient()
                    .prompt(prompt)//历史提示词和调用模型的参数整合到Prompt中,然后传递个大模型调用,不用再次设置如model,temperature,max_tokens等参数
                    .system(getSystemPrompt())// 设置系统提示词,即大模型的初始提示,用于引导大模型的行为
                    //.toolCallbacks(availableTools)//可用的所有工具和Mcp工具
                    .call()
                    .chatResponse();


            // 记录本次大模型调用的响应，用于后续 Act 方法中解析
            this.currentModelResponse = chatResponse;
            // 3、解析本次大模型调用的响应，获取是否有要调用的工具
           //  从响应中提取助手消息,即大模型的输出结果回答
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // 从助手消息中提取要调用的工具列表
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            // 从助手消息中提取输出的提示信息
            String result = assistantMessage.getText();
            log.info(getName() + "的思考：" + result);
            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
          //获取所有要调用的工具名称和对应参数-要调用工具所需要参数信息
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);// 输出要调用工具所需要参数信息
            // 工具列表为空时，不需要调用工具返回false
            if (toolCallList.isEmpty()) {
                // 只有不调用工具时，才需要手动记录助手消息
                getMessageList().add(assistantMessage);
                return false;
            } else {
                // 需要调用工具时，无需记录助手消息，因为调用工具时会自动记录think后该执行act()方法调用工具
                return true;
            }
        } catch (BusinessException | CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型调用失败，请稍后重试", e);
        }
    }

    /**
     * 执行工具调用,并返回处理结果
     *
     * @return 执行结果,执行工具，保存工具结果
     */
    @Override
    public String act() {
        //当前大模型没有响应,则不需要调用工具
        if (!currentModelResponse.hasToolCalls()) {
            return "没有工具需要调用";
        }
        // 包装消息历史(包含要执行的用户提示词)和模型参数(如model,temperature,max_tokens等)到提示词,防止在调用工具时,再次设置如model,temperature,max_tokens等参数
        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);

        // 执行工具调用,从当前大模型调用的响应中提取要调用的工具列表并执行工具调用
        // ToolExecutionResult工具执行完后的完整打包——里面既有纯粹的工具结果（toolResponses），又有把工具结果拼进去的完整对话历史（conversationHistory）
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, currentModelResponse);

        // 更新消息上下文，conversationHistory =各种 Message(第一条)+	大模型抉择AssistantMessage(第二条)+工具调用返回的结果ToolResponseMessage(第三条)
        setMessageList(new ArrayList<>(toolExecutionResult.conversationHistory()));

        // 得到本次工具调用的结果
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        // 判断是否调用了终止工具,判断是否调用了doTerminate工具
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));
        String answer = currentModelResponse.getResult().getOutput().getText();
        if (terminateToolCalled && getState() == AgentState.RUNNING) {
            // 终止工具和模型附带的进展说明都不能替代最终答复；用完整上下文整理结果。
            answer = generateFinalAnswer();
            setFinalAnswer(answer);
            if (getState() == AgentState.RUNNING) setState(AgentState.FINISHED);
        }
        String results = toolResponseMessage.getResponses().stream()
                .filter(response -> !response.name().equals("doTerminate"))
                .map(response -> "工具 " + response.name() + " 返回的结果：" + response.responseData())
                .collect(Collectors.joining("\n"));
        log.info(results);
        if (terminateToolCalled) return results;
        // 模型在调用工具时附带的用户可读说明也必须传回，不能只保存在日志/记忆中。
        return Stream.of(answer, results).filter(StrUtil::isNotBlank).collect(Collectors.joining("\n\n"));
    }

    private String generateFinalAnswer() {
        getMessageList().removeIf(message -> Boolean.TRUE.equals(message.getMetadata().get(ContextMemoryService.INTERNAL)));
        setMessageList(new ArrayList<>(getContextPreparer().apply(getMessageList())));
        if (getState() == AgentState.CANCELLED || Thread.currentThread().isInterrupted()) throw new CancellationException("任务已停止");
        List<Message> context = new ArrayList<>(getMessageList());
        context.add(new UserMessage(FINAL_ANSWER_PROMPT));
        var options = OpenAiChatOptions.builder().toolChoice("none")
                .toolCallbacks(List.of()).toolNames(java.util.Set.of()).tools(List.of())
                .internalToolExecutionEnabled(false).build();
        ChatResponse response = getChatClient().prompt(new Prompt(context, options))
                .system(getSystemPrompt()).call().chatResponse();
        if (response == null || response.getResult() == null || response.hasToolCalls()
                || StrUtil.isBlank(response.getResult().getOutput().getText())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未能生成最终回答，请重试");
        }
        AssistantMessage answer = response.getResult().getOutput();
        getMessageList().add(answer);
        return answer.getText();
    }
}

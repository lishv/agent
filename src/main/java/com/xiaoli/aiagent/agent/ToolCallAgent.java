package com.xiaoli.aiagent.agent;

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
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 */
@EqualsAndHashCode(callSuper = true)// 调用父类的equals方法
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

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
        this.toolCallingManager = ToolCallingManager.builder().build();

        // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
        this.chatOptions = DashScopeChatOptions.builder() //ToolCallingChatOptions.builder()若非阿里的大模型，需要这行代码
                .multiModel(true)// 开启多模型支持
                .withInternalToolExecutionEnabled(false)// 由于禁用 Spring AI 内置的工具调用机制,需要在application-local.yml中配置multi-model: true,并开启多模型支持
                .build();
    }

    /**
     * 处理当前状态并决定下一步是否需要进行工具调用
     *
     * @return 是否需要进行工具调用,获取工具调用决定
     */
    @Override
    public boolean think() {
        // 1、校验提示词，拼接下一步提示词,每轮 think() 都给模型一次当前步骤的引导提醒，防止模型"跑偏"：
        if (StrUtil.isNotBlank(getNextStepPrompt())) {
            // 将下一步提示词转化为UserMessage,防止模型"跑偏",添加到消息上下文
            UserMessage nextStepPrompt = new UserMessage(getNextStepPrompt());
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
                    .toolCallbacks(availableTools)//可用的所有工具和Mcp工具
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
        } catch (Exception e) {
            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
            getMessageList().add(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
            return false;
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
        setMessageList(toolExecutionResult.conversationHistory());//返回的是List<Message>类型包含例如UserMessage,AssistantMessage,ToolResponseMessage等消息类型

        // 得到本次工具调用的结果
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        // 判断是否调用了终止工具,判断是否调用了doTerminate工具
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));
        if (terminateToolCalled) {
            // 任务结束，更改状态
            setState(AgentState.FINISHED);
        }
        //返回工具调用的结果:每一个工具名称,返回结果
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 返回的结果：" + response.responseData())
                .collect(Collectors.joining("\n"));
        log.info(results);
        return results;
    }
}

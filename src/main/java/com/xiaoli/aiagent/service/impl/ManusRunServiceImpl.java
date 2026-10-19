package com.xiaoli.aiagent.service.impl;

import com.xiaoli.aiagent.agent.MyManus;
import com.xiaoli.aiagent.agent.model.AgentState;
import com.xiaoli.aiagent.conversation.Journal;
import com.xiaoli.aiagent.constant.ChatConstant;
import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.exception.ErrorCode;
import com.xiaoli.aiagent.exception.ThrowUtils;
import com.xiaoli.aiagent.interaction.HumanInteractionSession;
import com.xiaoli.aiagent.service.ContextMemoryService;
import com.xiaoli.aiagent.service.ManusRunService;
import com.xiaoli.aiagent.service.GeneratedFileService;

import jakarta.annotation.PreDestroy;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 管理正在执行的任务。每个任务单独绑定 AskHuman，结束后立即移除。 */
@Service
public class ManusRunServiceImpl implements ManusRunService {
    private final ToolCallback[] tools;
    private final ChatModel chatModel;
    private final ToolCallbackProvider mcpTools;
    /** 追问的最长等待时间（默认 10 分钟）。AI 向用户提问后，超过这个时间没收到回答就超时失败。 */
    private final Duration answerTimeout;

    /** 底层对话记忆仓库（Kryo 文件读写）。当 ContextMemoryService 没配置时，直接用它存取 AI 上下文。 */
    private final ChatMemoryRepository memoryRepository;

    /** runId → 正在执行的智能体实例。用来取消任务、判断任务是否在跑。任务结束后自动移除。 */
    private final Map<String, MyManus> runs = new ConcurrentHashMap<>();

    /** runId → 追问/回答交互通道。AI 需要向用户提问时用它来同步等待答案，超时抛异常。 */
    private final Map<String, HumanInteractionSession> interactions = new ConcurrentHashMap<>();

    /** chatId → 当前正在跑的那个智能体。保证一个会话同时只允许一个任务执行（互斥）。 */
    private final Map<String, MyManus> activeChats = new ConcurrentHashMap<>();

    /** runId → 所属用户 ID。用来校验追问/取消操作的归属权，防止越权。 */
    private final Map<String, Long> owners = new ConcurrentHashMap<>();

    /** 上下文压缩与记忆管理服务（可选）。配置了就用它做自动压缩，没配置就直接用 memoryRepository。 */
    private ContextMemoryService contextMemoryService;


    /** 注入上下文服务，并登记默认系统提示和工具开销，使空闲会话也能估算占用。 */
    @Autowired
    public void configureContexts(ContextMemoryService contexts, MyManus profile) {
        this.contextMemoryService = contexts;
        // 空闲会话也计入模型系统提示和现有工具定义；运行时使用该任务的完整工具集重新校准。
        contexts.registerProfile(
                "manus",
                profile.getSystemPrompt(),
                profile.getAvailableTools(),
                profile.getNextStepPrompt());
    }

    /** 绑定模型、工具、记忆仓库和追问超时设置；每轮任务另建独立智能体。 */
    public ManusRunServiceImpl(
            @Qualifier("allTools") ToolCallback[] tools,
            @Qualifier("openAiChatModel") ChatModel chatModel,
            ToolCallbackProvider mcpTools,
            @Value("${manus.human-answer-timeout-ms:600000}") long timeoutMillis,
            @Qualifier("manusChatMemoryRepository") ChatMemoryRepository memoryRepository) {
        this.tools = tools;
        this.chatModel = chatModel;
        this.mcpTools = mcpTools;
        this.answerTimeout = Duration.ofMillis(timeoutMillis);
        this.memoryRepository = memoryRepository;
    }

    /** 为内部任务自动生成记忆标识，委托完整启动入口执行。 */
    @Override
    public SseEmitter start(String message, String requestedRunId) {
        return start(message, requestedRunId, null);
    }

    /** 为内部任务指定记忆标识，委托完整启动入口执行，不绑定页面记录器。 */
    @Override
    public SseEmitter start(String message, String requestedRunId, String requestedChatId) {
        return start(message, requestedRunId, requestedChatId, 0L, null);
    }


    /**
     * 校验输入和并发状态，登记任务、绑定记录与上下文后启动执行；启动异常时释放资源。
     * @param message 任务描述
     * @param requestedRunId 任务 ID
     * @param requestedChatId 会话 ID
     * @param userId 用户 ID
     * @param journal 记录器
     * @return
     */
    @Override
    public SseEmitter start(
            String message,
            String requestedRunId,
            String requestedChatId,
            Long userId,
            Journal journal) {

        ThrowUtils.throwIf(message == null || message.isBlank() || message.length() > ChatConstant.MAX_INPUT_LENGTH, ErrorCode.PARAMS_ERROR, "请输入 1 到 " + ChatConstant.MAX_INPUT_LENGTH + " 个字符");
        // 校验任务 ID 格式
        String runId = resolveId(requestedRunId, "[A-Za-z0-9_-]{8,128}", "runId 格式不正确");
        // 校验会话 ID 格式
        String chatId = resolveId(requestedChatId, "[A-Za-z0-9_-]{1,128}", "chatId 格式不正确");
        //interaction 负责管理本次任务与用户之间的追问,管理“提问和等待回答”这件事。 journal：把问题、回答和状态保存到聊天记录里。
        HumanInteractionSession interaction = new HumanInteractionSession(runId, answerTimeout);

        MyManus agent = new MyManus(tools, chatModel, mcpTools, interaction::ask);
        agent.setToolContext(Map.of(GeneratedFileService.USER_ID, userId, GeneratedFileService.RUN_ID, runId));
        // 校验任务是否正在执行,  如果正在执行, 则抛出异常,  否则登记任务
        if (runs.putIfAbsent(runId, agent) != null) {
            throw new BusinessException(ErrorCode.CONFLICT_ERROR, "该任务正在执行");
        }
        // 校验会话是否正在执行,如果正在执行,则抛出异常,否则登记会话
        if (activeChats.putIfAbsent(chatId, agent) != null) {
            runs.remove(runId, agent);
            throw new BusinessException(ErrorCode.CONFLICT_ERROR, "该会话的上一条任务尚未结束");
        }

        // 登记本次运行id和会话,AI 等用户回答时，用 runId 找到那条同步等待的通道
        interactions.put(runId, interaction);
        // 登记本次运行id和用户id,
        owners.put(runId, userId);//用 runId 确认"这条任务是不是我的"，防止用户 A 取消或回答用户 B 的任务
        var contextOpened = new AtomicBoolean();//上下文是否已打开
        try {
            //把智能体执行过程中发生的事情，交给本轮对话的记录器保存，为了给前端展示
            bindJournal(agent, interaction, journal);
            // 准备上下文,如果上下文服务未配置,则直接使用 memoryRepository 保存记忆
            prepareContext(chatId, agent, contextOpened);
            return agent.runStream(
                    message,
                    emitter -> publishStart(emitter, runId, chatId, interaction, journal),
                    () -> {
                        release(runId, chatId, userId, agent, interaction, contextOpened);
                        if (journal != null) {
                            if (agent.getState() == AgentState.FINISHED) journal.complete();//complete是用来标记这个任务是否是正常结束的​
                            journal.finish();
                        }
                    });
        } catch (RuntimeException e) {
            release(runId, chatId, userId, agent, interaction, contextOpened);
            throw e;
        }
    }

    /** 未传入标识时生成 UUID，否则按对应长度和字符规则校验,用于生成任务ID或会话ID。 */
    private String resolveId(String requested, String pattern, String errorMessage) {
        String id = requested == null ? UUID.randomUUID().toString() : requested;
        ThrowUtils.throwIf(!id.matches(pattern), ErrorCode.PARAMS_ERROR, errorMessage);
        return id;
    }

    /** 将执行步骤、最终答案、错误、追问答案和取消动作绑定到本轮页面记录。 */
    private void bindJournal(MyManus agent, HumanInteractionSession interaction, Journal journal) {
        if (journal == null) return;
        agent.setStepObserver(journal::step);//智能体每一步都记录到聊天记录里
        agent.setFinalAnswerObserver(journal::finalAnswer);// 智能体最终答案也记录到聊天记录里
        agent.setErrorObserver(journal::failed);// 智能体执行错误也记录到聊天记录里
        interaction.setAnswerObserver(journal::answer);// 用户回答也记录到聊天记录里
        journal.onCancel(agent::cancelStream);// 取消任务也记录到聊天记录里
    }

    /** 恢复模型历史并绑定压缩、保存回调；未配置上下文服务时直接使用记忆仓库。 */
    private void prepareContext(String chatId, MyManus agent, AtomicBoolean contextOpened) {
        if (contextMemoryService == null) {
            // 未配置上下文服务,直接使用记忆仓库
            agent.setMessageList(new ArrayList<>(memoryRepository.findByConversationId(chatId)));
        } else {

            // 配置上下文服务,登记默认系统提示和工具开销,使空闲会话也能估算占用
            contextMemoryService.registerProfile(
                    "manus",
                    chatId,
                    agent.getSystemPrompt(),
                    agent.getAvailableTools(),
                    agent.getNextStepPrompt());
            agent.setMessageList(contextMemoryService.open("manus", chatId));// 打开上下文服务,获取会话记录
            //  上下文已打开,设置为 true
            contextOpened.set(true);
            // 绑定上下文准备器,用于压缩上下文
            agent.setContextPreparer(messages -> contextMemoryService.prepare("manus", chatId, messages));
        }
        agent.setMemoryWriter(messages -> saveMemory(chatId, agent, messages));
    }

    /** 仅允许仍占有该会话的智能体写入记忆，并过滤每步临时添加的控制提示。 */
    private void saveMemory(String chatId, MyManus agent, List<Message> messages) {
        synchronized (agent) {
            if (activeChats.get(chatId) != agent) return;
            var history =
                    messages.stream()
                            .filter(
                                    item ->
                                            !(item instanceof UserMessage)
                                                    || !agent.getNextStepPrompt()
                                                            .equals(item.getText()))
                            .toList();
            if (contextMemoryService != null) contextMemoryService.save("manus", chatId, history);
            else memoryRepository.saveAll(chatId, history);
        }
    }

    /** 先绑定追问推送通道，再发布任务与页面会话标识；浏览器断开不取消执行。 */
    /**
     * 发布任务与页面会话标识
     * @param emitter 事件推送器
     * @param runId 任务ID
     * @param chatId 会话ID
     * @param interaction 人机交互会话
     * @param journal 记忆记录
     */
    private void publishStart(
            SseEmitter emitter,
            String runId,
            String chatId,
            HumanInteractionSession interaction,
            Journal journal) {
        interaction.setPublisher(
                question -> {
                    if (journal != null) journal.question(question);
                    sendEvent(emitter, "human_question", question);
                });
        sendEvent(
                emitter,
                "run_started",
                Map.of("runId", runId, "chatId", journal == null ? chatId : journal.chatId()));
    }

    /** 结束和启动异常共用资源清理，按原实例移除运行登记，并且只关闭一次上下文。 */
    /**
     * 释放资源
     * @param runId 任务ID
     * @param chatId 会话ID
     * @param userId 用户ID
     * @param agent 智能体实例
     * @param interaction 人机交互会话
     * @param contextOpened 上下文是否已打开标志  用于判断是否需要关闭上下文服务
     */
    private void release(
            String runId,
            String chatId,
            Long userId,
            MyManus agent,
            HumanInteractionSession interaction,
            AtomicBoolean contextOpened) {
        interaction.close();
        interactions.remove(runId, interaction);
        owners.remove(runId, userId);
        runs.remove(runId, agent);
        synchronized (agent) {
            activeChats.remove(chatId, agent);
        }
        if (contextMemoryService != null && contextOpened.getAndSet(false)) contextMemoryService.close("manus", chatId);
    }

    /** 先校验当前用户拥有该任务，再提交追问答案。 */
    @Override
    public void answer(String runId, String questionId, String answer, Long userId) {
        requireOwner(runId, userId);
        answer(runId, questionId, answer);
    }

    /** 先校验任务归属，再取消当前运行实例。 */
    @Override
    public boolean cancel(String runId, Long userId) {
        requireOwner(runId, userId);
        return cancel(runId);
    }

    /** 确认当前用户拥有运行中的任务；无权限与已结束统一返回任务不存在。 */
    private void requireOwner(String runId, Long userId) {
        ThrowUtils.throwIf(
                runId == null || !userId.equals(owners.get(runId)),
                ErrorCode.TASK_ENDED,
                "任务已结束或不存在");
    }

    /** 校验问题标识与答案，交给任务专属交互通道接收；重复、过期或串任务的答案拒绝处理。 */
    @Override
    public void answer(String runId, String questionId, String answer) {
        if (runId == null
                || runId.isBlank()
                || questionId == null
                || questionId.isBlank()
                || answer == null
                || answer.isBlank()
                || answer.length() > ChatConstant.MAX_INPUT_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请提交有效的问题标识与回答（最多 " + ChatConstant.MAX_INPUT_LENGTH + " 字符）");
        }
        HumanInteractionSession interaction = interactions.get(runId);
        ThrowUtils.throwIf(interaction == null, ErrorCode.TASK_ENDED, "任务已结束，请重新发起");
        if (!interaction.answer(questionId, answer)) {
            throw new BusinessException(ErrorCode.CONFLICT_ERROR, "问题已回答、已过期或不属于当前任务");
        }
    }

    /** 按任务 ID 显式取消执行；任务已结束时返回 false。 */
    @Override
    public boolean cancel(String runId) {
        ThrowUtils.throwIf(runId == null || runId.isBlank(), ErrorCode.PARAMS_ERROR, "runId 不能为空");
        MyManus agent = runs.get(runId);
        if (agent == null) return false;
        agent.cancelStream();
        return true;
    }

    /** 尝试发送命名 SSE 事件；浏览器断开时忽略发送异常，让后台任务继续执行。 */
    private static void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException ignored) {
            // 问题与运行标识由服务器记录，浏览器断开不影响等待或继续执行。
        }
    }

    /** 取消全部任务、关闭追问等待，并清空进程内任务和归属登记。 */
    @PreDestroy
    @Override
    public void shutdown() {
        runs.values().forEach(MyManus::cancelStream);
        interactions.values().forEach(HumanInteractionSession::close);
        runs.clear();
        interactions.clear();
        activeChats.clear();
        owners.clear();
    }
}

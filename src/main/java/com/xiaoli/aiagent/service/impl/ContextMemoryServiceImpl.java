package com.xiaoli.aiagent.service.impl;

import com.xiaoli.aiagent.chatmemory.ContextTokenEstimator;
import com.xiaoli.aiagent.chatmemory.FileBasedChatMemoryRepository;
import com.xiaoli.aiagent.chatmemory.HookContextSummaryGenerator;
import com.xiaoli.aiagent.config.ContextMemoryProperties;
import com.xiaoli.aiagent.util.FilePathUtils;
import com.xiaoli.aiagent.domain.vo.ContextUsageView;
import com.xiaoli.aiagent.exception.ErrorCode;
import com.xiaoli.aiagent.exception.ThrowUtils;
import com.xiaoli.aiagent.service.ContextMemoryService;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/** 按应用和会话管理模型记忆；页面历史由 ConversationService 单独保留。 */
@Slf4j
@Service
public class ContextMemoryServiceImpl implements ContextMemoryService {
    private final ContextMemoryProperties contextMemoryProperties;
    private final ContextTokenEstimator contextTokenEstimator;//   计算 Token 占用
    private final HookContextSummaryGenerator summaries;
    private final Map<String, ChatMemoryRepository> chatMemoryRepositories;//记忆仓库
    private final Map<String, FileBasedChatMemoryRepository> backups;
    private final Map<String, ChatModel> models;
    //overheads 的 key 确实只有 "love" 和 "manus"，因为它存的是应用级别的默认固定开销。真正区分不同会话的是 states 里的 ContextMemoryState.overheadTokens，
    //overheads 是一个并发映射表，用于缓存应用级别的固定 Token 开销（系统提示 + 工具定义 + 控制提示）,应用名:固定 Token 开销
    private final Map<String, Integer> overheads = new ConcurrentHashMap<>();

    private final Map<String, ContextMemoryState> states = new ConcurrentHashMap<>();// 会话状态
    //executor 是虚拟线程池，负责在后台异步执行压缩任务，避免阻塞用户请求
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**构造函数是初始化上下文记忆服务，绑定各应用的模型和记忆仓库，建立独立备份目录并预先校验 Token 预算。 */
    public ContextMemoryServiceImpl(
            @Qualifier("loveChatMemoryRepository") ChatMemoryRepository love,
            @Qualifier("manusChatMemoryRepository") ChatMemoryRepository manus,
            @Qualifier("openAiChatModel") ChatModel loveModel,//dashScopeChatModel
            @Qualifier("openAiChatModel") ChatModel manusModel,
            ContextMemoryProperties contextMemoryconfig,
            ContextTokenEstimator contextTokenEstimator,
            HookContextSummaryGenerator summaries,
            @Value("${chat-memory.base-dir:${app.storage.base-dir:./tmp}/chat-memory}") String directory) {

        this.contextMemoryProperties = contextMemoryconfig;
        this.contextTokenEstimator = contextTokenEstimator;
        this.summaries = summaries;
        // 初始化记忆仓库
        chatMemoryRepositories = Map.of("love", love, "manus", manus);
        // 初始化模型
        models = Map.of("love", loveModel, "manus", manusModel);
        // 初始化备份目录
        Path root =FilePathUtils.chatMemoryRoot(directory);
        // 初始化备份目录
        backups =Map.of("love",
                        new FileBasedChatMemoryRepository( root.resolve("love/context-backups").toString()),
                        "manus",
                        new FileBasedChatMemoryRepository(root.resolve("manus/context-backups").toString()));
        // 校验 Token 预算
        contextMemoryconfig.budget("love");
        contextMemoryconfig.budget("manus");
    }

    /** 检查应用已注册且记忆标识符合字符与长度限制。 */
    private void validateKey(String app, String id) {
        ThrowUtils.throwIf(
                !chatMemoryRepositories.containsKey(app) || id == null || !id.matches("[A-Za-z0-9_-]{1,128}"),
                ErrorCode.PARAMS_ERROR,
                "上下文应用或会话标识无效");
    }

  /**① 恢复固定开销 token（从之前 registerProfile 存的 overheads 里取，兜底 12）；
   * ② 从持久层加载完整历史对话，用 ContextTokenEstimator 算 token 总数；
   * ③ 扫描历史里有没有压缩摘要，恢复压缩元数据（版本、时间、压缩效果）。所有上下文操作（registerProfile/open/prepare/save/usage）都必须先调 state() 拿状态对象，服务端重启后能从持久层完整恢
     * @param app 应用名称
     * @param chatId 会话ID
     * @return 上下文状态
     */
    private ContextMemoryState state(String app, String chatId) {
        validateKey(app, chatId);// 校验 app 和 chatId 非空、格式合法，防止 NPE 或非法 key
        return states.computeIfAbsent(
                app + ":" + chatId,
                key -> {
                    ContextMemoryState value = new ContextMemoryState();

                    // state里的overheadTokens 从overheads取
                    value.overheadTokens = overheads.getOrDefault(app, 12);// 获取该应用已登记的提示词和工具 Token 开销；未登记时使用基础估算值 12

                    var history = chatMemoryRepositories.get(app).findByConversationId(chatId);// 从持久化历史中获取会话记录

                    updateEstimate(value, history);// 更新估算占用

                    history.stream()//恢复压缩元数据
                            .filter(this::isSummary)// 只看标记为「压缩摘要」的消息
                            .findFirst() // 找第一条压缩摘要（如果有）
                            .ifPresent(
                                    message -> {
                                        //读取元数据对应key的数字字段,没有则返回0
                                        value.count = number(message, "contextRevision");// 压缩版本号（比如压了第3次）
                                        value.lastCompactedAt = number(message, "compactedAt");// 压缩时间戳
                                        value.before = (int) number(message, "beforeTokens");// 压缩前占用 Token 数
                                        value.after = (int) number(message, "afterTokens");// 压缩后占用 Token 数
                                        value.phase = "completed";
                                        value.message = "历史上下文已压缩，完整聊天记录仍保留";
                                    });
                    return value;
                });
    }

    /** 读取消息元数据中的数字字段；不存在或类型不符时返回 0。 */
    private long number(Message message, String key) {
        Object value = message.getMetadata().get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }

    /** 根据专用元数据标记判断消息是否为历史摘要。 */
    private boolean isSummary(Message message) {
        return Boolean.TRUE.equals(message.getMetadata().get(SUMMARY));
    }
    //登记整个应用的默认固定开销。
    //registerProfile(app, system, tools, nextStepPrompt) 预计算并缓存应用级别的固定 Token 开销（系统提示 + 工具定义 + 控制提示），存到 overheads Map 中
    @Override
    public void registerProfile(String app, String system, ToolCallback[] tools, String nextStepPrompt) {
        overheads.put(app, contextTokenEstimator.computeOverheadTokens(system, tools) + contextTokenEstimator.text(nextStepPrompt));
    }

    /** 用本轮实际配置更新指定会话。
     * @param app 应用名称
     * @param chatId 会话ID
     * @param systemPrompt 系统提示
     * @param tools 工具定义数组
     * @param nextStepPrompt 下一步提示
     */
    @Override
    public void registerProfile(String app, String chatId, String systemPrompt, ToolCallback[] tools, String nextStepPrompt) {
        ContextMemoryState contextMemoryState = state(app, chatId);

        // 计算本轮新配置的开销
        int overhead = contextTokenEstimator.computeOverheadTokens(systemPrompt, tools) + contextTokenEstimator.text(nextStepPrompt);

        contextMemoryState.lock.lock();
        try {
            requireAvailable(contextMemoryState);
            // 更新估算占用,历史记忆开销+本来的固定开销-历史的固定开销,(因为历史记忆中存在固定开销,所以要减去历史的固定开销)
            contextMemoryState.estimatedTokens = Math.max(0, contextMemoryState.estimatedTokens + overhead - contextMemoryState.overheadTokens);
            contextMemoryState.overheadTokens = overhead;// 更新,新的固定开销
        } finally {
            contextMemoryState.lock.unlock();
        }
    }

    /** 过滤只供模型执行控制使用的内部提示，保留实际用户、模型与工具消息。 */
    private List<Message> clean(List<Message> messages) {
        return messages.stream()
                .filter(message -> !Boolean.TRUE.equals(message.getMetadata().get(INTERNAL)))// 压缩前过滤内部提示
                .toList();
    }

    /** 合计历史消息和提示开销，更新估算总 Token 数并防止整数溢出。 */
    private void updateEstimate(ContextMemoryState state, List<Message> messages) {
        state.estimatedTokens = (int) Math.min(Integer.MAX_VALUE,(long) state.overheadTokens + contextTokenEstimator.computeMessagesTokens(messages));// 更新估算占用
    }

   //声明“这一轮任务要使用这份上下文”，并读取历史。
    @Override
    public List<Message> open(String app, String chatId) {
        ContextMemoryState state = state(app, chatId);//获取会话状态
        state.lock.lock();
        try {
            requireAvailable(state);
            ThrowUtils.throwIf(state.running, ErrorCode.CONFLICT_ERROR, "该会话已有任务在使用上下文");
            state.running = true;
            //读取并返回历史
            return new ArrayList<>(chatMemoryRepositories.get(app).findByConversationId(chatId));
        } finally {
            state.lock.unlock();
        }
    }

    /** 在模型调用前的安全位置保存或压缩历史，并检查压缩后的输入是否仍超过预算。 */
    @Override
    public List<Message> prepare(String app, String id, List<Message> messages) {
        ContextMemoryState state = state(app, id);
        state.lock.lock();
        try {
            //1. 重新估算占用
            requireAvailable(state);
            var history = new ArrayList<>(clean(messages));
            updateEstimate(state, history);// 更新本次估算占用

            //2. 判断是否需要压缩
            // 如果估算占用超过预算，需要压缩历史, 否则直接返回历史历史
            List<Message> prepared = needsCompression(app, state) ? compact(app, id, state, history) : history;
            // 成功压缩已由 compact 保存，普通请求和未压缩的历史在这里保存一次。
            // 3.保存本次有效历史,如果是未压缩的历史，需要保存到 kryo 文件
            if (prepared == history) chatMemoryRepositories.get(app).saveAll(id, history);
            //4.检查是否仍超过输入预算
            ThrowUtils.throwIf(contextMemoryProperties.isEnabled() && state.estimatedTokens > contextMemoryProperties.budget(app).inputBudget(), ErrorCode.PARAMS_ERROR, "上下文超过配置预算，暂时无法安全压缩；请减少本次输入或调整上下文预算");
            //5.返回模型应该使用的历史
            return prepared;
        } finally {
            state.lock.unlock();
        }
    }

    /** 用传进来的完整历史覆盖保存。。 */
    @Override
    public void save(String app, String id, List<Message> messages) {
        ContextMemoryState state = state(app, id);
        state.lock.lock();
        try {
            if (state.deleted) return;
            var history = clean(messages);
            //是把过滤后的消息历史全量写入对应应用（love/manus）的 kryo 文件，实现 AI 推理上下文的持久化。
            chatMemoryRepositories.get(app).saveAll(id, history);
            updateEstimate(state, history);// 更新估算占用
        } finally {
            state.lock.unlock();
        }
    }

    /** 只提供新增消息，由这里读取旧历史再追加。 */
    @Override
    public void append(String app, String chatId, List<Message> additions) {
        ContextMemoryState state = state(app, chatId);
        state.lock.lock();
        try {
            if (state.deleted) return;
            var history = new ArrayList<>(chatMemoryRepositories.get(app).findByConversationId(chatId));
            history.addAll(additions);// 追加新消息到历史记录
            chatMemoryRepositories.get(app).saveAll(chatId, history);//持久化历史记录
            updateEstimate(state, history);// 更新估算占用
        } finally {
            state.lock.unlock();
        }
    }

    /** 结束本轮占用，并检查是否需要后台压缩。 */
    @Override
    public void close(String app, String id) {
        ContextMemoryState state = state(app, id);
        state.lock.lock();
        try {
            state.running = false;
            if (state.deleted) return;
            // 如果估算占用超过预算，需要压缩历史, 否则直接返回历史历史
            if (needsCompression(app, state)) schedule(app, id, state);
        } finally {
            state.lock.unlock();
        }
    }

    /** 记录用户点击的手动压缩请求。 */
    @Override
    public ContextUsageView request(String app, String chatId) {
        ContextMemoryState state = state(app, chatId);
        if (!contextMemoryProperties.isEnabled()) return usage(app, chatId);// 如果上下文压缩功能未开启，直接返回当前占用
        synchronized (state) {
            requireAvailable(state);
            // 如果当前正在压缩或已请求压缩，直接返回当前占用
            if (state.requested || "compressing".equals(state.phase)) return usage(app, chatId);
            // 记录用户点击的手动压缩请求
            state.requested = true;
            // 如果当前模型任务正在使用上下文，等待安全位置压缩
            state.phase = "queued";
            state.message = state.running ? "已安排在下一个安全位置压缩" : "正在准备压缩上下文";
        }
        //立即安排后台任务 schedule()压缩
        if (!state.running) schedule(app, chatId, state);
        return usage(app, chatId);// 返回当前占用
    }

    //安排空闲会话的后台压缩。
    private void schedule(String app, String id, ContextMemoryState state) {
        if (state.deleted || !state.scheduled.compareAndSet(false, true)) return;
        state.phase = "queued";
        state.message = state.requested ? "正在准备压缩上下文" : "已安排自动压缩上下文";
        executor.submit(
                () -> {
                    state.lock.lock();
                    try {
                        if (state.deleted || state.running) return;
                        // 读取当前历史记录
                        var history = chatMemoryRepositories.get(app).findByConversationId(id);
                        updateEstimate(state, history);//计算当前估算占用给state

                        // 如果估算占用超过预算，需要压缩历史, 否则直接返回历史历史
                        if (needsCompression(app, state)) compact(app, id, state, history);
                    } finally {
                        state.lock.unlock();
                        state.scheduled.set(false);
                        if (!state.deleted
                                && state.requested
                                && !state.running
                                && !executor.isShutdown()) schedule(app, id, state);
                    }
                });
    }

    /** 综合启用状态、手动请求、自动阈值和失败重试时间判断是否需要压缩。 */
    private boolean needsCompression(String app, ContextMemoryState state) {
        /*
        会话未删除
        并且压缩总开关开启
        并且满足下面之一：

        ① 有手动压缩请求

        ② 自动压缩开启
           并且自动重试冷却已结束
           并且占用达到阈值
         */
        return !state.deleted
                && contextMemoryProperties.isEnabled()
                && (state.requested
                        || contextMemoryProperties.isAutoEnabled()
                                && System.currentTimeMillis() >= state.retryAfter
                                && state.estimatedTokens
                                        >= contextMemoryProperties.budget(app).inputBudget()
                                                * contextMemoryProperties.getThreshold());
    }

    //决定从哪里把历史切成两部分。
    private int cutoff(String app, ContextMemoryState state, List<Message> history) {
        List<Integer> turns =
                IntStream.range(0, history.size())// 过滤出用户消息的索引
                        .filter(index -> history.get(index) instanceof UserMessage)// 过滤出用户消息的索引
                        .boxed()
                        .toList();
        if (turns.isEmpty()) return 0;

        List<Integer> safe = safeCutoffs(history);//找出不会拆开工具请求与结果的位置。即找到纯用户消息/助手消息的索引,工具调用对闭合的位置。

        int keep = Math.min(contextMemoryProperties.getRetainTurns(), turns.size());// 计算需要保留的轮数
        int cut = turns.get(turns.size() - keep);// 计算压缩位置
        //计算压缩位置的占用占用是否超过阈值
        int limit = (int) (contextMemoryProperties.budget(app).inputBudget() * contextMemoryProperties.getThreshold());
        // 计算压缩位置的占用占用是否超过阈值
        /*
        这段循环逐步减少保留的对话轮数（keep--），切割点后移到下一个用户消息位置（cut 跳到 turns 中的下一个索引），
        直到保留的 token 降到阈值以下。keep > 1 保证至少保留最新一轮用户消息。
         */
        while (keep > 1 && retainedTokens(state, history, cut) >= limit) {
            keep--;
            cut = turns.get(turns.size() - keep);//压缩点+1
        }
        int selected = cut;// 记录压缩位置
        // 找到最近的安全位置
        cut =
                safe.stream()
                        .filter(index -> index <= selected)
                        .mapToInt(Integer::intValue)
                        .max()
                        .orElse(0);
        // 一个长智能体任务也可压缩已经完成的步骤，保留最新一步或尚未完成的工具链。
        //当普通策略保留的 token 仍超限时，遍历安全切割点，
        // 把更多已完成的旧步骤压缩成摘要，只保留最新的用户消息和未完成的工具链，直到 token 降到目标值以下。
        if ("manus".equals(app) && retainedTokens(state, history, cut) >= limit) {
            int target = (int) (contextMemoryProperties.budget(app).inputBudget() * contextMemoryProperties.getTargetRatio());
            int latestUserTokens = contextTokenEstimator.computeMessagesTokens(List.of(history.get(turns.getLast())));
            for (int candidate : safe) {
                if (candidate <= cut) continue;
                cut = candidate;
                if (retainedTokens(state, history, cut) + latestUserTokens <= target) break;
            }
        }
        return cut;
    }

    /**找出不会拆开工具请求与结果的位置。  List<Integer> safe = safeCutoffs(history);//找出不会拆开工具请求与结果的位置。即找到纯用户消息/助手消息的索引。 */
    private List<Integer> safeCutoffs(List<Message> history) {
        List<Integer> safe = new ArrayList<>(List.of(0));
        Set<String> pending = new HashSet<>();

        for (int i = 0; i < history.size(); i++) {
            Message message = history.get(i);
            // 如果是助手消息，记录工具调用 ID
            if (message instanceof AssistantMessage assistant) {
                // 记录助手消息中的工具调用 ID
                for (var call : assistant.getToolCalls()) pending.add(call.id());
            }
            // 如果是工具响应消息，移除对应的工具调用 ID
            if (message instanceof ToolResponseMessage response) {
                for (var result : response.getResponses()) pending.remove(result.id());
            }
            if (pending.isEmpty()) safe.add(i + 1);
        }
        return safe;
    }

    /** 提前估算保留方案是否太大。 */
    private int retainedTokens(ContextMemoryState state, List<Message> history, int cut) {
        return state.overheadTokens//固定成本
                + contextTokenEstimator.computeMessagesTokens(history.subList(cut, history.size()))// 切点之后的原文占用
                + contextMemoryProperties.getSummaryMaxTokens();//新的摘要最大 token 数
    }

    /** 生成摘要，确认占用减少后备份原历史并保存新历史；失败时保留原记忆并延后重试。 */
    private List<Message> compact(
            String app, String id, ContextMemoryState state, List<Message> history) {
        //1.选择切点。
        int cut = cutoff(app, state, history);
        boolean manual;
        synchronized (state) {
            manual = state.requested;
            //2.消费掉手动请求。
            state.requested = false;
            state.phase = cut == 0 ? "noop" : "compressing";
        }
        if (cut == 0) {
            state.phase = "noop";
            state.message = "暂无可安全压缩的较早历史，已保留近期原文";
            return history;
        }
        state.phase = "compressing";
        state.message = "正在生成历史摘要，完整聊天记录仍保留";
        int before = state.estimatedTokens;//压缩前的占用
        try {
            //3.取出准备摘要的旧消息。
            var older =
                    history.subList(0, cut).stream()
                            .filter(message -> !(message instanceof SystemMessage))
                            .toList();
            if (older.isEmpty()) {
                state.phase = "noop";
                state.message = "暂无需要压缩的历史";
                return history;
            }
            //5.调用摘要生成器。利用 HookContextSummaryGenerator 生成摘要。
            String summary =
                    summaries.summarize(
                            models.get(app),
                            contextTokenEstimator.transcript(older),//4.把消息转换成摘要模型能阅读的文字。
                            contextMemoryProperties.budget(app).inputBudget(),//摘要输入预算 725000
                            contextMemoryProperties.getSummaryMaxTokens());//摘要最大 token 数40960
            //6.创建摘要消息，组合新历史
            var summaryMessage = summaryMessage(summary, state, before);

            List<Message> result = compactedHistory(history, cut, summaryMessage);
            //7.确认占用减少。计算压缩后的占用。
            int after = contextTokenEstimator.computeMessagesTokens(result) + state.overheadTokens;
            if (after >= before) {
                state.phase = "noop";
                state.message = "摘要未减少占用，已保留原上下文";
                return history;
            }
            //8.备份原历史。
            summaryMessage.getMetadata().put("afterTokens", after);
            // 摘要调用可能耗时较长，删除已发出时不要再生成备份或写入压缩结果。
            if (state.deleted) return history;
            backups.get(app).saveAll(id + "_" + UUID.randomUUID(), history);
            //9.更新压缩状态。
            chatMemoryRepositories.get(app).saveAll(id, result);
            state.count++;
            state.lastCompactedAt = number(summaryMessage, "compactedAt");
            state.before = before;
            state.after = after;
            state.estimatedTokens = after;
            state.phase = "completed";
            state.retryAfter = 0;
            state.message = (manual ? "手动压缩完成" : "自动压缩完成") + "，完整聊天记录仍保留";
            return result;
        } catch (Exception error) {
            state.phase = "error";
            state.message = "上下文压缩失败，已保留原记忆，可稍后重试";
            state.retryAfter = System.currentTimeMillis() + 60000;
            log.warn("上下文压缩未完成，已保留原记忆：{}", error.getClass().getSimpleName());
            return history;
        }
    }

    /** 把摘要文字包装成一条可识别的消息。*/
    private AssistantMessage summaryMessage(String summary, ContextMemoryState state, int before) {
        var message =
                AssistantMessage.builder().content("【历史记忆摘要；仅供参考，以用户当前要求为准】\n" + summary).build();
        message.getMetadata().put(SUMMARY, true);
        message.getMetadata().put("contextRevision", state.count + 1);
        message.getMetadata().put("compactedAt", System.currentTimeMillis());
        message.getMetadata().put("beforeTokens", before);
        return message;
    }

    /** 组装最终要保存的新列表。系统+摘要+近期原文。被压缩区域内的最新用户目标也以原文保留。*/
    private List<Message> compactedHistory(List<Message> history, int cut, AssistantMessage summary) {

        List<Message> result =
                new ArrayList<>(
                        history.subList(0, cut).stream()
                                .filter(SystemMessage.class::isInstance)//只保留 SystemMessage，其他全过滤掉
                                .toList());

        result.add(summary);
        Message latestUser =
                history.stream()
                        .filter(UserMessage.class::isInstance)
                        .reduce((first, second) -> second)//取最新的用户消息。作用：确保压缩后的历史包含最新的用户目标。
                        .orElse(null);
        List<Message> recent = history.subList(cut, history.size());//获取切点后的消息。
        if (latestUser != null && !recent.contains(latestUser)) result.add(latestUser);
        result.addAll(recent);
        return result;
    }

    /** 把占用和压缩状态整理给前端。 */
    @Override
    public ContextUsageView usage(String app, String id) {
        ContextMemoryState state = state(app, id);
        requireAvailable(state);
        var budget = contextMemoryProperties.budget(app);
        return new ContextUsageView(
                contextMemoryProperties.isEnabled(),
                true,
                state.estimatedTokens,//当前总成本
                budget.getContextWindowTokens(),//上下文窗口大小
                budget.getResponseReserveTokens(),//响应预留令牌
                budget.inputBudget(),//输入预算
                Math.round(state.estimatedTokens * 1000.0 / budget.inputBudget()) / 10.0,//占用占比
                contextMemoryProperties.isAutoEnabled(),//是否自动压缩
                contextMemoryProperties.getThreshold() * 100,//压缩阈值
                contextMemoryProperties.getRetainTurns(),//保留轮数
                state.phase,//当前状态
                state.lastCompactedAt,//最近一次压缩时间
                state.before,//压缩前占用
                state.after,//压缩后占用
                state.count,//压缩次数
                state.message);//压缩状态描述
    }

    /** 拒绝访问已经删除的会话。 */
    private void requireAvailable(ContextMemoryState state) {
        ThrowUtils.throwIf(state.deleted, ErrorCode.NOT_FOUND_ERROR, "对话不存在或已删除");
    }

    /** 删除模型记忆和压缩备份，并阻止迟到写入。 */
    @Override
    public void delete(String app, String id) {
        validateKey(app, id);
        // 删除不读取文件内容，因此损坏的 Kryo 文件也能清理。
        ContextMemoryState state = states.computeIfAbsent(app + ":" + id, key -> new ContextMemoryState());
        // 墓碑在锁外先置位；等待中的模型回调和异步压缩在拿到锁后都会放弃写入。
        // 本进程保留墓碑，避免迟到的回调创建新 ContextMemoryState 后复活文件。
        state.deleted = true;
        state.lock.lock();
        try {
            state.requested = false;
            state.running = false;
            chatMemoryRepositories.get(app).deleteByConversationId(id);//删除模型记忆
            backups.get(app).deleteCompactionBackups(id);//删除压缩备份
        } finally {
            state.lock.unlock();
        }
    }

    /** 停止后台压缩执行器，并中断尚未结束的任务。 */
    @PreDestroy
    @Override
    public void shutdown() {
        executor.shutdownNow();
    }
}

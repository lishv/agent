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
    private final ContextMemoryProperties config;
    private final ContextTokenEstimator contextTokenEstimator;//   计算 Token 占用
    private final HookContextSummaryGenerator summaries;
    private final Map<String, ChatMemoryRepository> chatMemoryRepositories;//记忆仓库
    private final Map<String, FileBasedChatMemoryRepository> backups;
    private final Map<String, ChatModel> models;
    private final Map<String, Integer> overheads = new ConcurrentHashMap<>();
    private final Map<String, ContextMemoryState> states = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 绑定各应用的模型和记忆仓库，建立独立备份目录并预先校验 Token 预算。 */
    public ContextMemoryServiceImpl(
            @Qualifier("loveChatMemoryRepository") ChatMemoryRepository love,
            @Qualifier("manusChatMemoryRepository") ChatMemoryRepository manus,
            @Qualifier("dashScopeChatModel") ChatModel loveModel,
            @Qualifier("openAiChatModel") ChatModel manusModel,
            ContextMemoryProperties contextMemoryconfig,
            ContextTokenEstimator contextTokenEstimator,
            HookContextSummaryGenerator summaries,
            @Value("${chat-memory.base-dir:${app.storage.base-dir:./tmp}/chat-memory}") String directory) {

        this.config = contextMemoryconfig;
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

    /** 按应用和记忆标识复用状态，首次访问时从持久化历史恢复占用及压缩元数据。 */
    private ContextMemoryState state(String app, String id) {
        validateKey(app, id);
        return states.computeIfAbsent(
                app + ":" + id,
                key -> {
                    ContextMemoryState value = new ContextMemoryState();
                    value.overheadTokens = overheads.getOrDefault(app, 12);
                    var history = chatMemoryRepositories.get(app).findByConversationId(id);
                    updateEstimate(value, history);
                    history.stream()
                            .filter(this::isSummary)
                            .findFirst()
                            .ifPresent(
                                    message -> {
                                        value.count = number(message, "contextRevision");
                                        value.lastCompactedAt = number(message, "compactedAt");
                                        value.before = (int) number(message, "beforeTokens");
                                        value.after = (int) number(message, "afterTokens");
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

    /** 登记应用默认的系统提示、工具定义与控制提示所占 Token开销。 */
    @Override
    public void registerProfile(String app, String system, ToolCallback[] tools, String controlPrompt) {
        overheads.put(app, contextTokenEstimator.overhead(system, tools) + contextTokenEstimator.text(controlPrompt));
    }

    /** 在会话锁内用实际任务配置更新提示和工具开销，并同步估算占用开销。 */
    @Override
    public void registerProfile(String app, String chatId, String system, ToolCallback[] tools, String controlPrompt) {
        ContextMemoryState contextMemoryState = state(app, chatId);
        // 计算新配置的开销
        int overhead = contextTokenEstimator.overhead(system, tools) + contextTokenEstimator.text(controlPrompt);
        contextMemoryState.lock.lock();
        try {
            requireAvailable(contextMemoryState);
            contextMemoryState.estimatedTokens = Math.max(0, contextMemoryState.estimatedTokens + overhead - contextMemoryState.overheadTokens);
            contextMemoryState.overheadTokens = overhead;
        } finally {
            contextMemoryState.lock.unlock();
        }
    }

    /** 过滤只供模型执行控制使用的内部提示，保留实际用户、模型与工具消息。 */
    private List<Message> clean(List<Message> messages) {
        return messages.stream()
                .filter(message -> !Boolean.TRUE.equals(message.getMetadata().get(INTERNAL)))
                .toList();
    }

    /** 合计历史消息和提示开销，更新估算 Token 数并防止整数溢出。 */
    private void updateEstimate(ContextMemoryState state, List<Message> messages) {
        state.estimatedTokens =
                (int)
                        Math.min(
                                Integer.MAX_VALUE,
                                (long) state.overheadTokens + contextTokenEstimator.messages(messages));
    }

    /** 在会话锁内独占上下文并恢复历史；已删除或已被占用时拒绝打开。 */
    @Override
    public List<Message> open(String app, String chatId) {
        ContextMemoryState state = state(app, chatId);//获取会话状态
        state.lock.lock();
        try {
            requireAvailable(state);
            ThrowUtils.throwIf(state.running, ErrorCode.CONFLICT_ERROR, "该会话已有任务在使用上下文");
            state.running = true;
            //
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
            requireAvailable(state);
            var history = new ArrayList<>(clean(messages));
            updateEstimate(state, history);
            var prepared =
                    needsCompression(app, state) ? compact(app, id, state, history) : history;
            // 成功压缩已由 compact 保存，普通请求和未压缩的历史在这里保存一次。
            if (prepared == history) chatMemoryRepositories.get(app).saveAll(id, history);
            ThrowUtils.throwIf(
                    config.isEnabled() && state.estimatedTokens > config.budget(app).inputBudget(),
                    ErrorCode.PARAMS_ERROR,
                    "上下文超过配置预算，暂时无法安全压缩；请减少本次输入或调整上下文预算");
            return prepared;
        } finally {
            state.lock.unlock();
        }
    }

    /** 在会话锁内过滤控制消息、覆盖保存历史并更新占用；忽略删除后的迟到写入。 */
    @Override
    public void save(String app, String id, List<Message> messages) {
        ContextMemoryState state = state(app, id);
        state.lock.lock();
        try {
            if (state.deleted) return;
            var history = clean(messages);
            chatMemoryRepositories.get(app).saveAll(id, history);
            updateEstimate(state, history);
        } finally {
            state.lock.unlock();
        }
    }

    /** 在会话锁内追加消息并更新占用；已删除会话不再写入。 */
    @Override
    public void append(String app, String id, List<Message> additions) {
        ContextMemoryState state = state(app, id);
        state.lock.lock();
        try {
            if (state.deleted) return;
            var history = new ArrayList<>(chatMemoryRepositories.get(app).findByConversationId(id));
            history.addAll(additions);
            chatMemoryRepositories.get(app).saveAll(id, history);
            updateEstimate(state, history);
        } finally {
            state.lock.unlock();
        }
    }

    /** 释放上下文占用，必要时安排后台压缩；已删除会话不再调度任务。 */
    @Override
    public void close(String app, String id) {
        ContextMemoryState state = state(app, id);
        state.lock.lock();
        try {
            state.running = false;
            if (state.deleted) return;
            if (needsCompression(app, state)) schedule(app, id, state);
        } finally {
            state.lock.unlock();
        }
    }

    /** 登记一次手动压缩请求；运行中等待安全位置，空闲时调度后台执行。 */
    @Override
    public ContextUsageView request(String app, String id) {
        ContextMemoryState state = state(app, id);
        if (!config.isEnabled()) return usage(app, id);
        synchronized (state) {
            requireAvailable(state);
            if (state.requested || "compressing".equals(state.phase)) return usage(app, id);
            state.requested = true;
            state.phase = "queued";
            state.message = state.running ? "已安排在下一个安全位置压缩" : "正在准备压缩上下文";
        }
        if (!state.running) schedule(app, id, state);
        return usage(app, id);
    }

    /** 为同一上下文最多登记一个后台压缩任务，取得锁后再次检查执行和删除状态。 */
    private void schedule(String app, String id, ContextMemoryState state) {
        if (state.deleted || !state.scheduled.compareAndSet(false, true)) return;
        state.phase = "queued";
        state.message = state.requested ? "正在准备压缩上下文" : "已安排自动压缩上下文";
        executor.submit(
                () -> {
                    state.lock.lock();
                    try {
                        if (state.deleted || state.running) return;
                        var history = chatMemoryRepositories.get(app).findByConversationId(id);
                        updateEstimate(state, history);
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
        return !state.deleted
                && config.isEnabled()
                && (state.requested
                        || config.isAutoEnabled()
                                && System.currentTimeMillis() >= state.retryAfter
                                && state.estimatedTokens
                                        >= config.budget(app).inputBudget()
                                                * config.getThreshold());
    }

    /** 按近期轮数及 Token 预算选择工具调用闭合的截断边界，长智能体任务可压缩已完成步骤。 */
    private int cutoff(String app, ContextMemoryState state, List<Message> history) {
        List<Integer> turns =
                IntStream.range(0, history.size())
                        .filter(index -> history.get(index) instanceof UserMessage)
                        .boxed()
                        .toList();
        if (turns.isEmpty()) return 0;
        List<Integer> safe = safeCutoffs(history);
        int keep = Math.min(config.getRetainTurns(), turns.size());
        int cut = turns.get(turns.size() - keep);
        int limit = (int) (config.budget(app).inputBudget() * config.getThreshold());
        while (keep > 1 && retainedTokens(state, history, cut) >= limit) {
            keep--;
            cut = turns.get(turns.size() - keep);
        }
        int selected = cut;
        cut =
                safe.stream()
                        .filter(index -> index <= selected)
                        .mapToInt(Integer::intValue)
                        .max()
                        .orElse(0);
        // 一个长智能体任务也可压缩已经完成的步骤，保留最新一步或尚未完成的工具链。
        if ("manus".equals(app) && retainedTokens(state, history, cut) >= limit) {
            int target = (int) (config.budget(app).inputBudget() * config.getTargetRatio());
            int latestUserTokens = contextTokenEstimator.messages(List.of(history.get(turns.getLast())));
            for (int candidate : safe) {
                if (candidate <= cut) continue;
                cut = candidate;
                if (retainedTokens(state, history, cut) + latestUserTokens <= target) break;
            }
        }
        return cut;
    }

    /** 扫描没有待返回工具调用的边界，避免将工具请求与对应结果拆开。 */
    private List<Integer> safeCutoffs(List<Message> history) {
        List<Integer> safe = new ArrayList<>(List.of(0));
        Set<String> pending = new HashSet<>();
        for (int i = 0; i < history.size(); i++) {
            Message message = history.get(i);
            if (message instanceof AssistantMessage assistant) {
                for (var call : assistant.getToolCalls()) pending.add(call.id());
            }
            if (message instanceof ToolResponseMessage response) {
                for (var result : response.getResponses()) pending.remove(result.id());
            }
            if (pending.isEmpty()) safe.add(i + 1);
        }
        return safe;
    }

    /** 估算保留区域、摘要以及系统和工具定义的总 Token 开销。 */
    private int retainedTokens(ContextMemoryState state, List<Message> history, int cut) {
        return state.overheadTokens
                + contextTokenEstimator.messages(history.subList(cut, history.size()))
                + config.getSummaryMaxTokens();
    }

    /** 生成摘要，确认占用减少后备份原历史并保存新历史；失败时保留原记忆并延后重试。 */
    private List<Message> compact(
            String app, String id, ContextMemoryState state, List<Message> history) {
        int cut = cutoff(app, state, history);
        boolean manual;
        synchronized (state) {
            manual = state.requested;
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
        int before = state.estimatedTokens;
        try {
            var older =
                    history.subList(0, cut).stream()
                            .filter(message -> !(message instanceof SystemMessage))
                            .toList();
            if (older.isEmpty()) {
                state.phase = "noop";
                state.message = "暂无需要压缩的历史";
                return history;
            }
            String summary =
                    summaries.summarize(
                            models.get(app),
                            contextTokenEstimator.transcript(older),
                            config.budget(app).inputBudget(),
                            config.getSummaryMaxTokens());
            var summaryMessage = summaryMessage(summary, state, before);
            List<Message> result = compactedHistory(history, cut, summaryMessage);
            int after = contextTokenEstimator.messages(result) + state.overheadTokens;
            if (after >= before) {
                state.phase = "noop";
                state.message = "摘要未减少占用，已保留原上下文";
                return history;
            }
            summaryMessage.getMetadata().put("afterTokens", after);
            // 摘要调用可能耗时较长，删除已发出时不要再生成备份或写入压缩结果。
            if (state.deleted) return history;
            backups.get(app).saveAll(id + "_" + UUID.randomUUID(), history);
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

    /** 创建带修订号、时间和压缩前占用的摘要消息，供重启后恢复状态。 */
    private AssistantMessage summaryMessage(String summary, ContextMemoryState state, int before) {
        var message =
                AssistantMessage.builder().content("【历史记忆摘要；仅供参考，以用户当前要求为准】\n" + summary).build();
        message.getMetadata().put(SUMMARY, true);
        message.getMetadata().put("contextRevision", state.count + 1);
        message.getMetadata().put("compactedAt", System.currentTimeMillis());
        message.getMetadata().put("beforeTokens", before);
        return message;
    }

    /** 组合旧系统消息、摘要和近期原文，被压缩区域内的最新用户目标也以原文保留。 */
    private List<Message> compactedHistory(
            List<Message> history, int cut, AssistantMessage summary) {
        List<Message> result =
                new ArrayList<>(
                        history.subList(0, cut).stream()
                                .filter(SystemMessage.class::isInstance)
                                .toList());
        result.add(summary);
        Message latestUser =
                history.stream()
                        .filter(UserMessage.class::isInstance)
                        .reduce((first, second) -> second)
                        .orElse(null);
        List<Message> recent = history.subList(cut, history.size());
        if (latestUser != null && !recent.contains(latestUser)) result.add(latestUser);
        result.addAll(recent);
        return result;
    }

    /** 返回估算占用、配置预算及压缩状态，不返回模型记忆的具体内容。 */
    @Override
    public ContextUsageView usage(String app, String id) {
        ContextMemoryState state = state(app, id);
        requireAvailable(state);
        var budget = config.budget(app);
        return new ContextUsageView(
                config.isEnabled(),
                true,
                state.estimatedTokens,
                budget.getContextWindowTokens(),
                budget.getResponseReserveTokens(),
                budget.inputBudget(),
                Math.round(state.estimatedTokens * 1000.0 / budget.inputBudget()) / 10.0,
                config.isAutoEnabled(),
                config.getThreshold() * 100,
                config.getRetainTurns(),
                state.phase,
                state.lastCompactedAt,
                state.before,
                state.after,
                state.count,
                state.message);
    }

    /** 拒绝访问已有删除标记的会话上下文。 */
    private void requireAvailable(ContextMemoryState state) {
        ThrowUtils.throwIf(state.deleted, ErrorCode.NOT_FOUND_ERROR, "对话不存在或已删除");
    }

    /** 先设置删除标记阻止迟到写入，再在会话锁内清理记忆与摘要备份，兼容损坏文件。 */
    @Override
    public void delete(String app, String id) {
        validateKey(app, id);
        // 删除不读取文件内容，因此损坏的 Kryo 文件也能清理。
        ContextMemoryState state =
                states.computeIfAbsent(app + ":" + id, key -> new ContextMemoryState());
        // 墓碑在锁外先置位；等待中的模型回调和异步压缩在拿到锁后都会放弃写入。
        // 本进程保留墓碑，避免迟到的回调创建新 ContextMemoryState 后复活文件。
        state.deleted = true;
        state.lock.lock();
        try {
            state.requested = false;
            state.running = false;
            chatMemoryRepositories.get(app).deleteByConversationId(id);
            backups.get(app).deleteCompactionBackups(id);
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

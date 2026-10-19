package com.xiaoli.aiagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiaoli.aiagent.conversation.ConversationTranscriptStore;
import com.xiaoli.aiagent.conversation.FileConversationJournal;
import com.xiaoli.aiagent.conversation.Journal;
import com.xiaoli.aiagent.constant.ChatConstant;
import com.xiaoli.aiagent.domain.entity.ChatSession;
import com.xiaoli.aiagent.domain.vo.ConversationViews.Detail;
import com.xiaoli.aiagent.domain.vo.ConversationViews.Summary;
import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.exception.ErrorCode;
import com.xiaoli.aiagent.exception.ThrowUtils;
import com.xiaoli.aiagent.mapper.ChatSessionMapper;
import com.xiaoli.aiagent.service.ContextMemoryService;
import com.xiaoli.aiagent.service.ConversationService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 数据库保存归属与路径；服务器写入页面消息文件，客户端无需上传或覆盖聊天历史。 */
@Service
public class ConversationServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession>
        implements ConversationService {
    private final ObjectMapper json;
    private final ConversationTranscriptStore transcripts;
    private final ContextMemoryService memory;
    // 固定数量的锁按会话分组，串行化开始、改名和删除，避免为每条历史永久保留一把锁。
    private final Object[] sessionLocks =
            java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    // 本轮对话的消息记录器。
    private final Map<String, FileConversationJournal> active = new ConcurrentHashMap<>();

    /** 绑定会话 Mapper、JSON 与记忆服务，并解析页面历史的存储根目录。 */
    public ConversationServiceImpl(
            ChatSessionMapper mapper,
            ObjectMapper json,
            ContextMemoryService memory,
            @Value("${chat-memory.base-dir:${app.storage.base-dir:./tmp}/chat-memory}") String directory) {
        this.baseMapper = mapper;
        this.json = json;
        this.memory = memory;
        transcripts = new ConversationTranscriptStore(json, directory);
    }

    /** 将数据库 UTC 时间转换为前端使用的毫秒时间戳。 */
    private static long epoch(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** 使用 UTC 生成数据库时间，避免依赖服务器本地时区。 */
    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 返回“用户 ID_会话 ID”格式的记忆标识，本方法不生成文件路径。 */
    @Override
    public String memoryKey(ChatSession row) {
        return row.getUserId() + "_" + row.getId();
    }

    /** 查询当前用户最近 100 个未删除会话，可按应用筛选，并补充运行状态。 */
    @Override
    public List<Summary> list(Long userId, String appId) {
        if (appId != null) validateApp(appId);
        return lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .eq(appId != null, ChatSession::getAppId, appId)
                .orderByDesc(ChatSession::getUpdatedAt)
                .last("LIMIT 100")
                .list()
                .stream()
                .map(this::summary)
                .toList();
    }

    /** 使用 MyBatis-Plus 查询有效会话，再校验所属用户及可选的应用类型。 */
    @Override
    public ChatSession own(Long userId, String chatId, String appId) {
        return requireOwned(getById(chatId), userId, appId);
    }

    /** 直接校验已有记录的归属、删除状态与应用，避免为了校验再次查询数据库。 */
    private ChatSession requireOwned(ChatSession row, Long userId, String appId) {
        if (row == null || !userId.equals(row.getUserId()) || row.getIsDeleted() != 0 || appId != null && !appId.equals(row.getAppId()))
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "对话不存在或已删除");
        return row;
    }

    /** 返回有效会话的完整页面历史；运行中读取快照，空闲时读取持久化文件。 */
    @Override
    public Detail detail(Long userId, String chatId) {
        ChatSession row = own(userId, chatId, null);
        Journal journal = active.get(chatId);
        ThrowUtils.throwIf(
                journal == null && !transcripts.exists(row),
                ErrorCode.SERVICE_UNAVAILABLE,
                "对话记录文件暂时不可用，已保留会话信息");
        List<ObjectNode> messages = journal == null ? transcripts.read(row) : journal.snapshot();
        return new Detail(
                row.getId(),
                row.getAppId(),
                row.getTitle(),
                epoch(row.getCreatedAt()),
                epoch(row.getUpdatedAt()),
                messages,
                journal != null && journal.running());
    }

    /** 校验输入后串行创建或恢复会话，登记本轮记录器并持久化用户消息。 */
    @Override
    public Journal begin(Long userId, String appId, String requestedId, String message) {
        //验证是love或manus应用的id
        validateApp(appId);
        ThrowUtils.throwIf(message == null || message.isBlank() || message.length() > ChatConstant.MAX_INPUT_LENGTH, ErrorCode.PARAMS_ERROR, "请输入 1 到 " + ChatConstant.MAX_INPUT_LENGTH + " 个字符");
        String conversationId = requestedId == null ? UUID.randomUUID().toString() : requestedId;// 会话id,若未指定则自动生成
        ThrowUtils.throwIf(!conversationId.matches("[A-Za-z0-9_-]{1,64}"), ErrorCode.PARAMS_ERROR, "会话 ID 格式不正确");
        synchronized (sessionLock(conversationId)) {
            // 从数据库获取会话的元数据
            ChatSession row = findOrCreate(userId, appId, conversationId, message.trim());
            // 创建或恢复会话的记录器
            FileConversationJournal journal =
                    new FileConversationJournal(
                            row,
                            memoryKey(row),
                            json,
                            transcripts,
                            this::touchSession,
                            closed -> active.remove(row.getId(), closed));
            //即如果active中已存在该会话,则说明该会话正在回答,则抛出异常,提示稍候再试,若无则创建新记录
            ThrowUtils.throwIf(active.putIfAbsent(conversationId, journal) != null, ErrorCode.CONFLICT_ERROR, "这段对话正在回答，请稍候");
            try {
                journal.addUser(message.trim(), false);
                return journal;
            } catch (RuntimeException e) {
                active.remove(conversationId, journal);
                throw e;
            }
        }
    }

    /** 恢复已有有效会话或创建元数据；并发插入冲突时重新校验已存在的记录。 */
    private ChatSession findOrCreate(Long userId, String appId, String id, String message) {
        ChatSession existing = baseMapper.findIncludingDeleted(id);
        if (existing != null) return requireOwned(existing, userId, appId);//未找到会话,则校验已存在的记录
    // 未找到会话,则创建新记录
        ChatSession row = new ChatSession();
        row.setId(id);
        row.setUserId(userId);
        row.setAppId(appId);
        row.setTitle(message.substring(0, Math.min(message.length(), 24)));
        row.setMemoryFilePath(appId + "/" + memoryKey(row) + ".kryo");
        row.setTranscriptFilePath(appId + "/" + memoryKey(row) + ".json");
        row.setCreatedAt(now());
        row.setUpdatedAt(now());
        row.setIsDeleted(0);
        try {
            save(row);
            return row;
        } catch (DuplicateKeyException e) {
            return own(userId, id, appId);
        }
    }

    /** 将数据库元数据和本进程的运行登记组合为会话列表视图。 */
    private Summary summary(ChatSession row) {
        return new Summary(
                row.getId(),
                row.getAppId(),
                row.getTitle(),
                epoch(row.getCreatedAt()),
                epoch(row.getUpdatedAt()),
                active.containsKey(row.getId()));
    }

    /** 按会话 ID 选取固定锁，串行化该会话的开始、改名和删除操作。 */
    private Object sessionLock(String id) {
        return sessionLocks[Math.floorMod(id.hashCode(), sessionLocks.length)];
    }

    /** 校验归属和标题后更新数据库，返回新标题及运行状态，不影响当前任务。 */
    @Override
    public Summary rename(Long userId, String id, String title) {
        String value = title == null ? "" : title.strip();
        ThrowUtils.throwIf(
                value.isBlank() || value.length() > 100,
                ErrorCode.PARAMS_ERROR,
                "对话名需为 1 至 100 个字符");
        synchronized (sessionLock(id)) {
            ChatSession row = own(userId, id, null);
            LocalDateTime updatedAt = now();
            boolean changed =
                    lambdaUpdate()
                            .eq(ChatSession::getId, id)
                            .eq(ChatSession::getUserId, userId)
                            .set(ChatSession::getTitle, value)
                            .set(ChatSession::getUpdatedAt, updatedAt)
                            .update();
            ThrowUtils.throwIf(!changed, ErrorCode.NOT_FOUND_ERROR, "对话不存在或已删除");
            row.setTitle(value);
            row.setUpdatedAt(updatedAt);
            return summary(row);
        }
    }

    /** 先逻辑删除当前用户的会话，再终止任务、清理记忆与记录；重复请求继续清理。 */
    @Override
    public void delete(Long userId, String id) {
        synchronized (sessionLock(id)) {
            ChatSession row = baseMapper.findIncludingDeleted(id);
            ThrowUtils.throwIf(
                    row == null || !userId.equals(row.getUserId()),
                    ErrorCode.NOT_FOUND_ERROR,
                    "对话不存在");
            validateApp(row.getAppId());
            // 清理只接受该用户、该应用和该会话生成的路径，防止误删其他会话文件。
            String key = memoryKey(row);
            ThrowUtils.throwIf(
                    !(row.getAppId() + "/" + key + ".json").equals(row.getTranscriptFilePath())
                            || !(row.getAppId() + "/" + key + ".kryo")
                                    .equals(row.getMemoryFilePath()),
                    ErrorCode.SYSTEM_ERROR,
                    "会话存储路径配置异常");
            Path transcript = transcripts.path(row);
            // 数据库先标记不可访问。文件清理失败不回滚；重复 DELETE 会继续清理，客户端可重试。
            if (row.getIsDeleted() == 0) {
                remove(
                        Wrappers.<ChatSession>lambdaQuery()
                                .eq(ChatSession::getId, id)
                                .eq(ChatSession::getUserId, userId));
            }
            FileConversationJournal journal = active.get(id);
            if (journal != null) journal.discard();
            memory.delete(row.getAppId(), key);
            transcripts.delete(transcript);
        }
    }

    /** 校验会话归属后取消正在运行的记录器；不存在运行任务时返回 false。 */
    @Override
    public boolean cancel(Long userId, String id) {
        own(userId, id, null);
        Journal journal = active.get(id);
        if (journal == null) return false;
        journal.cancel();
        return true;
    }

    /** 只接受 love 与 manus 两种已支持的应用标识。 */
    private static void validateApp(String appId) {
        ThrowUtils.throwIf(!List.of("love", "manus").contains(appId), ErrorCode.PARAMS_ERROR, "应用类型不正确");
    }

    /** 页面消息强制保存后更新会话时间，数据库操作仍由 ServiceImpl 统一处理。 */
    private void touchSession(ChatSession row) {
        lambdaUpdate()
                .eq(ChatSession::getId, row.getId())
                .set(ChatSession::getUpdatedAt, now())
                .update();
    }
}

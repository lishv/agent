package com.xiaoli.aiagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiaoli.aiagent.domain.entity.ChatSession;
import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
/*
┌─────────────────────────────────────────────┐
│  记录消息（谁在说什么）                        │
│  ├─ addUser()         → 客人说的话            │
│  ├─ step()            → AI 执行的步骤         │
│  ├─ loveChunk()       → AI 边想边输出（流式） │
│  ├─ finalAnswer()     → AI 最终完整回答       │
│  ├─ question()        → AI 向客人追问         │
│  ├─ answer()          → 客人回答追问          │
│  └─ failed()          → AI 执行失败           │
├─────────────────────────────────────────────┤
│  控制状态（现在什么情况）                      │
│  ├─ running()         → 还在营业吗？          │
│  ├─ onCancel()        → 注册"客人取消"时该做啥 │
│  ├─ cancel()          → 客人说"我不听了"      │
│  ├─ complete()        → AI 说"我说完了"       │
│  ├─ finish()          → 关账本，存柜子        │
│  └─ snapshot()        → 把账本复印一份给客人看 │
└─────────────────────────────────────────────┘
 */
/** 负责记录消息与执行状态,每轮对话独立创建的记录器；维护页面消息、执行状态及文件保存，不依赖业务 Service。 */
@Slf4j
public final class FileConversationJournal implements Journal {
    private final ChatSession row;//数据库中这条会话的元数据（id、userId、appId、文件路径、标题等），不可变。
    private final String memoryId;//模型记忆标识，格式为 userId_id，传给 ContextMemoryService 读写 .kryo 文件。
    private final ObjectMapper json;//Jackson JSON 序列化器，保证 messages 中每条消息的 JSON 结构统一。
    private final ConversationTranscriptStore transcripts;//负责 .json 文件的原子读写，隔离文件 IO 细节。
    private final Consumer<ChatSession> onSave;//强制保存后回调外部更新 ChatSession.updatedAt 等元数据（数据库时间戳刷新）。
    private final Consumer<FileConversationJournal> onClose;//记录器关闭时回调外部把自身从"活跃会话表"中移除，释放并发锁。
    private final List<ObjectNode> messages;//页面消息列表，包含历史恢复的消息 + 本轮新增的全部消息，是 .json 文件的内存镜像。
    private final int firstMessage;//本轮新增消息的起始索引，用于 finish() 时只扫描本轮消息判断状态。
    private ObjectNode loveReply;//本轮流式追加的 AI 最终回复引用（loveChunk 逐块累加 content），用于 failed/complete 时更新状态。
    private boolean closed;//    记录器是否已关闭。关闭后不再接受任何写入，所有方法直接 return。
    private boolean completed; //    AI 执行是否正常完成（未出错、未取消），用于 finish() 时决定是否补写 stopped 状态。
    private boolean failed;//    AI 执行是否抛出异常，用于 failed() 和 finish() 时的状态判断。
    private long lastSave;//    上次落盘的纳秒时间戳，用于 save(false) 时做 200ms 限流，避免流式片段过于频繁写文件。
    private Runnable cancellation = () -> {};//    取消时触发的外部回调（通常绑定 agent::cancelStream），由 onCancel() 注册，cancel()/discard() 时调用。

    /**
     * 恢复已有页面消息并创建本轮记录器；保存与关闭回调由会话服务提供。
     *
     * @param row 当前会话元数据
     * @param memoryId 按用户隔离的模型记忆标识
     * @param json 应用统一的 JSON 配置
     * @param transcripts 页面记录文件存储
     * @param onSave 强制保存后更新会话元数据的回调
     * @param onClose 关闭后按记录器实例移除运行登记的回调
     */
    public FileConversationJournal(
            ChatSession row,
            String memoryId,
            ObjectMapper json,
            ConversationTranscriptStore transcripts,
            Consumer<ChatSession> onSave,
            Consumer<FileConversationJournal> onClose) {
        this.row = row;
        this.memoryId = memoryId;
        this.json = json;
        this.transcripts = transcripts;
        this.onSave = onSave;
        this.onClose = onClose;
        this.messages = transcripts.read(row);
        this.firstMessage = messages.size();
        stopPendingMessages();
    }

    /** 返回页面与数据库使用的会话 ID。 */
    @Override
    public String chatId() {
        return row.getId();
    }

    /** 返回包含用户 ID 的模型记忆标识，用于隔离不同用户的上下文。 */
    @Override
    public String memoryId() {
        return memoryId;
    }

    /** 返回本轮记录器是否尚未关闭；该状态与浏览器连接是否断开无关。 */
    @Override
    public synchronized boolean running() {
        return !closed;
    }

    /** 登记取消回调；记录器已经关闭时在锁外立即执行，避免遗漏取消。 */
    @Override
    public void onCancel(Runnable callback) {
        boolean alreadyClosed;
        synchronized (this) {
            cancellation = callback;
            alreadyClosed = closed;
        }
        if (alreadyClosed) callback.run();//    记录器已关闭，立即执行传入的回调。
    }

    /** 删除会话时先关闭记录器并移除运行登记，再在锁外取消任务，阻止迟到写入。在任务刚开始时OnCancle就注册一个终止输出流的回调*/
    public void discard() {
        Runnable callback;
        synchronized (this) {
            closed = true;
            onClose.accept(this);//    记录器关闭时,回调外部把自身从"活跃会话表"中移除，释放并发锁。
            callback = cancellation;//    记录器已关闭，立即执行传入的回调。
        }
        try {
            callback.run();
        } catch (RuntimeException e) {
            log.warn("删除对话时取消任务失败，继续清理记忆", e);
        }
    }

    /** 在记录器锁外调用任务取消动作，再保存中止状态并关闭记录器。 */
    @Override
    public void cancel() {
        Runnable callback;
        synchronized (this) {
            callback = cancellation;
        }
        callback.run();
        finish();
    }

    /** 创建带唯一标识、角色、内容、状态和时间的页面消息，并加入当前历史。 */
    private ObjectNode message(String role, String content, String status) {
        ObjectNode item =
                json.createObjectNode()
                        .put("id", UUID.randomUUID().toString())
                        .put("role", role)
                        .put("content", content)
                        .put("status", status)
                        .put("createdAt", System.currentTimeMillis());//    消息创建时间，用于排序和展示。
        item.putArray("questions");
        messages.add(item);
        return item;
    }

    /** 追加用户消息并立即保存；answer 标记其是否来自智能体追问回答。 */
    @Override
    public synchronized void addUser(String content, boolean answer) {
        if (closed) return;
        message("user", content, "done").put("humanAnswer", answer);
        save(true);
    }

    /** 将文本片段累加到本轮正式回答，最多每 200 毫秒保存一次中间结果。 */
    @Override
    public synchronized void loveChunk(String chunk) {
        if (closed) return;
        if (loveReply == null)
            loveReply = message("assistant", "", "streaming").put("kind", "final");
        loveReply.put("content", loveReply.path("content").asText() + chunk);
        save(false);
    }

    /** 追加带步骤编号的进度消息并立即保存，与最终答案区分展示。 */
    @Override
    public synchronized void step(int number, String content) {
        if (closed) return;
        message("assistant", content, "done").put("stepNumber", number).put("kind", "progress");//progress  进度消息，与最终答案消息区分展示。
        save(true);
    }

    /** 追加独立的最终答案消息并立即保存；关闭后的回调不再写入。 */
    @Override
    public synchronized void finalAnswer(String content) {
        if (closed) return;
        message("assistant", content, "done").put("kind", "final");//  final 最终答案消息，与进度消息区分展示。
        save(true);
    }

    /** 追加待回答的追问消息并立即保存，便于浏览器重新打开会话时恢复。 */
    @Override
    public synchronized void question(Object question) {
        if (closed) return;
        ObjectNode questionNode = json.valueToTree(question);
        questionNode.put("answer", "");
        questionNode.put("expired", false);
        message("assistant", "", "waiting").withArray("questions").add(questionNode);
        save(true);
    }

    /** 按问题 ID 回填答案、结束等待状态，并追加一条用户回答；未匹配时忽略。 */
    @Override
    public synchronized void answer(String questionId, String answer) {
        if (closed) return;
        for (ObjectNode item : messages)
            for (var question : item.path("questions")) {
                if (questionId.equals(question.path("questionId").asText())) {
                    ((ObjectNode) question).put("answer", answer);
                    item.put("status", "done");
                    addUser(answer, true);
                    return;
                }
            }
    }

    /** 标记本轮失败并保存错误状态，不把异常内容或堆栈写入页面消息。 */
    @Override
    public synchronized void failed(Throwable error) {
        if (closed) return;
        failed = true;
        if (loveReply != null) loveReply.put("status", "error");
        else message("assistant", "", "error");
        save(true);
    }

    /** 标记正常完成并更新流式回答状态；最终保存交给 finish 处理。 */
    @Override
    public synchronized void complete() {
        completed = true;
        if (loveReply != null && !closed) loveReply.put("status", "done");
    }

    /** 返回消息列表的深拷贝，防止读取方修改运行中的历史。 */
    @Override
    public synchronized List<ObjectNode> snapshot() {
        return messages.stream().map(ObjectNode::deepCopy).toList();
    }

    /** 保存完成、失败或中止状态，随后关闭记录器并释放登记；重复调用直接返回。 */
    @Override
    public synchronized void finish() {
        if (closed) return;
        boolean pending =
                messages.subList(firstMessage, messages.size()).stream()
                        .anyMatch(item -> List.of("streaming", "waiting").contains(item.path("status").asText()));
        //  检查是否有未结束的文本或追问消息。
        stopPendingMessages();
        if (!completed && !failed && !pending)
            message("assistant", "", "stopped");
        try {
            save(true);
        } finally {
            closed = true;
            onClose.accept(this);
        }
    }

    /** 将未结束的文本或追问标为中止，并让所有尚未回答的问题过期。 */
    private void stopPendingMessages() {
        messages.forEach(
                item -> {
                    if (List.of("streaming", "waiting").contains(item.path("status").asText()))
                        item.put("status", "stopped");
                    item.path("questions")
                            .forEach(
                                    question -> {
                                        if (question.path("answer").asText("").isEmpty())
                                            ((ObjectNode) question).put("expired", true);
                                    });
                });
    }

    /** 按间隔或强制保存页面记录；强制保存同时更新会话时间，写入失败转换为业务异常。 */
    private void save(boolean force) {
        if (!force && System.nanoTime() - lastSave < 200_000_000L) return;
        Path target = transcripts.path(row);
        try {
            transcripts.write(target, messages);
            lastSave = System.nanoTime();
            if (force) onSave.accept(row);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "无法保存对话记录，请稍后重试", e);
        }
    }
}

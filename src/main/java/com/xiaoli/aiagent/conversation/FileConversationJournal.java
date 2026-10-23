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
    private final ObjectMapper json;//Jackson JSON 序列化器，保证 messagesJson 中每条消息的 JSON 结构统一。
    private final ConversationTranscriptStore conversationTranscriptStore;//负责 .json 文件的原子读写，隔离文件 IO 细节。
    private final Consumer<ChatSession> onSave;//强制保存后回调外部更新 ChatSession.updatedAt 等元数据（数据库时间戳刷新）。
    private final Consumer<FileConversationJournal> onClose;//记录器关闭时回调外部把自身从"活跃会话表"中移除，释放并发锁。

    private final List<ObjectNode> messagesJson;//页面消息列表，包含历史恢复的消息 + 本轮新增的全部消息，是 .json 文件的内存镜像。

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
     * @param conversationTranscriptStore 页面记录文件存储
     * @param onSave 强制保存后更新会话元数据的回调
     * @param onClose 关闭后按记录器实例移除运行登记的回调
     */
    public FileConversationJournal(
            ChatSession row,
            String memoryId,
            ObjectMapper json,
            ConversationTranscriptStore conversationTranscriptStore,
            Consumer<ChatSession> onSave,
            Consumer<FileConversationJournal> onClose) {
        this.row = row;
        this.memoryId = memoryId;
        this.json = json;
        this.conversationTranscriptStore = conversationTranscriptStore;
        this.onSave = onSave;
        this.onClose = onClose;
        this.messagesJson = conversationTranscriptStore.read(row);
        this.firstMessage = messagesJson.size();
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
        callback.run();//执行cancellation的回调
        finish();
    }

    /** 创建带唯一标识、角色、内容、状态和时间的页面消息，并加入当前历史。 */
    private ObjectNode createMessage(String role, String content, String status) {
        ObjectNode item =
                json.createObjectNode()//创建一个json对象
                        .put("id", UUID.randomUUID().toString())
                        .put("role", role)
                        .put("content", content)
                        .put("status", status)
                        .put("createdAt", System.currentTimeMillis());//    消息创建时间，用于排序和展示。
        item.putArray("questions");
        messagesJson.add(item);
        return item;
    }

    /** 追加用户消息并立即保存；answer 标记其是否来自智能体追问回答。
     * true:这是用户回答智能体追问
     * false:这是用户主动发起的对话
     * 这里的true和false是给前端看的,值存入json文件的humanAnswer字段中。
     * @param content
     * @param answer
     */
    @Override
    public synchronized void addUser(String content, boolean answer) {
        if (closed) return;
        ObjectNode jsonNodes = createMessage("user", content, "done");//createMessage会创建一个json->item然后内置messages.add(item);   // ← 关键：加入 messages 列表
        jsonNodes.put("humanAnswer", answer);
        //这里的true和false是给前端看的,值存入json文件的humanAnswer字段中。对message不会有任何影响,因为message只存在createMessage操作
        saveMessagesJson(true);//    保存用户消息到文件,并立即刷新缓存。
    }

    /** 将文本片段累加到本轮正式回答，最多每 200 毫秒保存一次中间结果。 */
    @Override
    public synchronized void loveChunk(String chunk) {
        if (closed) return;
        if (loveReply == null)
            loveReply = createMessage("assistant", "", "streaming").put("kind", "final");
        loveReply.put("content", loveReply.path("content").asText() + chunk);
        saveMessagesJson(false);//    保存正式回答到文件,并立即刷新缓存。
    }

    /** 追加带步骤编号的进度消息并立即保存，与最终答案区分展示。 */
    @Override
    public synchronized void step(int number, String content) {
        if (closed) return;
        createMessage("assistant", content, "done").put("stepNumber", number).put("kind", "progress");//progress  进度消息，与最终答案消息区分展示。
        saveMessagesJson(true);
    }

    /** 追加独立的最终答案消息并立即保存；关闭后的回调不再写入。 */
    @Override
    public synchronized void finalAnswer(String content) {
        if (closed) return;
        createMessage("assistant", content, "done").put("kind", "final");//  final 最终答案消息，与进度消息区分展示。
        saveMessagesJson(true);
    }

    /** 追加待回答的追问消息并立即保存，便于浏览器重新打开会话时恢复。 */
    @Override
    public synchronized void question(Object question) {
        if (closed) return;
        /*
                                                                    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                                                                    Java 对象（原物）：
                                                                    Map<String, Object> question = Map.of(
                                                                        "type", "input",
                                                                        "placeholder", "请输入邮箱",
                                                                        "required", true
                                                                    );

                                                                                    ↓ json.valueToTree(question) 翻译

                                                                    Jackson ObjectNode（可编辑的 JSON 草稿纸）：
                                                                    {
                                                                      "type": "input",
                                                                      "placeholder": "请输入邮箱",
                                                                      "required": true
                                                                    }
                                                                    ↑ 这就是 questionNode！你可以随便往里面加字段、改值！
                                                                     {
                                                                          "role": "assistant",
                                                                          "content": "",
                                                                          "status": "waiting",
                                                                          "timestamp": 1745123456789,
                                                                          "questions": [
                                                                            {
                                                                              "id": "q_001",
                                                                              "type": "input",
                                                                              "placeholder": "请输入您的邮箱",
                                                                              "required": true,
                                                                              "answer": "",       // 待用户填写
                                                                              "expired": false    // 有效
                                                                            }
                                                                          ]
                                                                         }
                                                                    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

         */
        ObjectNode questionNode = json.valueToTree(question);//将map转换为ObjectNode的json字符串,并在json添加answer和expired字段
        questionNode.put("answer", "");//answer字段,待用户填写
        questionNode.put("expired", false);//expired字段,用于标记问题是否过期
        createMessage("assistant", "", "waiting").withArray("questions").add(questionNode);//.withArray("questions")：给这条消息加一个 "questions" 数组字段（如果没有就创建，有就返回）中
        saveMessagesJson(true);//强制保存,更新会话时间,并调用onSave.accept(row)方法,移除active中的该会话,并更新会话时间
    }

    /** 按问题 ID 回填答案、结束等待状态，并追加一条用户回答；未匹配时忽略。 */
    @Override
    public synchronized void answer(String questionId, String answer) {
        if (closed) return;
        for (ObjectNode item : messagesJson)
            for (var question : item.path("questions")) {
                if (questionId.equals(question.path("questionId").asText())) {
                    ((ObjectNode) question).put("answer", answer);
                    item.put("status", "done");
                    addUser(answer, true);//将用户回答添加到用户回答列表中,并设置为已处理
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
        else createMessage("assistant", "", "error");
        saveMessagesJson(true);
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
        return messagesJson.stream().map(ObjectNode::deepCopy).toList();
    }

    /** 保存完成、失败或中止状态，随后关闭记录器并释放登记；重复调用直接返回。 */
    @Override
    public synchronized void finish() {
        if (closed) return;
        boolean pending =
                messagesJson.subList(firstMessage, messagesJson.size()).stream()
                        .anyMatch(item -> List.of("streaming", "waiting").contains(item.path("status").asText()));
        //  检查是否有未结束的文本或追问消息。
        stopPendingMessages();
        if (!completed && !failed && !pending)
            createMessage("assistant", "", "stopped");
        try {
            saveMessagesJson(true);
        } finally {
            closed = true;
            onClose.accept(this);
        }
    }

    /** 将未结束的文本或追问标为中止，并让所有尚未回答的问题过期。 */
    private void stopPendingMessages() {
        messagesJson.forEach(
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

    /** save 方法主要保存页面消息 JSON 文件；当 force=true 时，额外刷新一下数据库里该会话的 updated_at 时间戳。它不管模型记忆，也不改会话的其他元数据。 */
    private void saveMessagesJson(boolean force) {
        if (!force && System.nanoTime() - lastSave < 200_000_000L) return;

        Path target = conversationTranscriptStore.path(row);//根据row获取对应的文件路径
        try {
            conversationTranscriptStore.writeToJson(target, messagesJson);//将messages写入文件,保存效果为json字符串

            lastSave = System.nanoTime();//更新lastSave为当前时间,用于下一次保存间隔计算

            if (force) onSave.accept(row);//如果是强制保存,则调用onSave.accept(row)方法,移除active中的该会话
            //force=true 时触发 onSave 回调,更新数据库中的会话时间→closed=true 时从自身静态 active 缓存移除。force 参数是性能与安全的开关：关键节点（存追问/关闭会话）用 true（强制刷盘+通知外部），临时更新用 false（攒一批再刷盘）。
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "无法保存对话记录，请稍后重试", e);
        }
    }
}

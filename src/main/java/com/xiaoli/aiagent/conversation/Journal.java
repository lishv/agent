package com.xiaoli.aiagent.conversation;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** 运行中的会话记录契约，相当于本轮会话的记录员；调用者无需依赖具体文件实现。 */
public interface Journal {
    /** 返回页面和数据库使用的会话 ID。 */
    String chatId();

    /** 返回按用户隔离的模型记忆标识。 */
    String memoryId();

    /** 返回记录器是否尚未关闭，不代表浏览器仍连接着 SSE。 */
    boolean running();

    /** 绑定取消动作；若记录器已关闭，立即执行该动作。 */
    void onCancel(Runnable callback);

    /** 执行已绑定的取消动作，并保存中止状态、关闭记录器。 */
    void cancel();

    /** 写入用户消息并立即保存，answer 表示是否为智能体追问的回答。 */
    void addUser(String content, boolean answer);

    /** 累加恋爱助手的流式文本，按写入间隔保存中间结果。 */
    void loveChunk(String chunk);

    /** 保存一条带步骤编号的智能体进度消息。 */
    void step(int number, String content);

    /** 单独保存最终答案，供页面区分任务进度和正式回答。 */
    void finalAnswer(String content);

    /** 保存智能体追问及未回答状态，供页面恢复交互。 */
    void question(Object question);

    /** 按问题 ID 回填答案，并追加一条用户回答消息；未匹配时不修改。 */
    void answer(String questionId, String answer);

    /** 标记本轮执行失败并保存错误状态，不把异常堆栈写入页面消息。 */
    void failed(Throwable error);

    /** 标记本轮正常完成；最终持久化由 finish 负责。 */
    void complete();

    /** 返回消息的深拷贝，避免读取方修改运行中的记录。 */
    List<ObjectNode> snapshot();

    /** 保存最终状态并释放运行登记；多次调用只处理第一次。 */
    void finish();
}

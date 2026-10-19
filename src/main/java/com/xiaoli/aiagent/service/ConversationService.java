package com.xiaoli.aiagent.service;

import com.xiaoli.aiagent.conversation.Journal;
import com.xiaoli.aiagent.domain.entity.ChatSession;
import com.xiaoli.aiagent.domain.vo.ConversationViews.Detail;
import com.xiaoli.aiagent.domain.vo.ConversationViews.Summary;

import java.util.List;

/** 会话业务契约；所有读取和修改都按当前登录用户校验归属。 */
public interface ConversationService {
    /** 返回“用户 ID_会话 ID”格式的记忆标识；应用目录由调用方单独指定。 */
    String memoryKey(ChatSession row);

    /** 列出当前用户最近 100 个未删除会话，appId 为空时查询所有应用。 */
    List<Summary> list(Long userId, String appId);

    /** 返回归属于当前用户的有效会话；appId 非空时还必须匹配应用。 */
    ChatSession own(Long userId, String chatId, String appId);

    /** 返回持久化或运行中的完整页面消息；历史文件丢失时提示暂时不可用。 */
    Detail detail(Long userId, String chatId);

    /** 创建或恢复会话，登记本轮记录器并写入用户消息；同一会话不能重复启动。 */
    Journal begin(Long userId, String appId, String requestedId, String message);

    /** 修改标题，不改变会话 ID、消息和正在执行的任务。 */
    Summary rename(Long userId, String chatId, String title);

    /** 逻辑删除数据库记录，停止任务并清理该会话的记录、记忆和压缩备份。 */
    void delete(Long userId, String id);

    /** 校验归属后取消任务；存在运行中的记录器时返回 true，否则返回 false。 */
    boolean cancel(Long userId, String id);
}

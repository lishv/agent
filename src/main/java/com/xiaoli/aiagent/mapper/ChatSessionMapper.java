package com.xiaoli.aiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaoli.aiagent.domain.entity.ChatSession;

import org.apache.ibatis.annotations.Select;

/** 会话数据访问；普通操作复用 BaseMapper，仅额外声明包含逻辑删除记录的查询。 */
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
    /** 按 ID 查询所有状态的记录，用于阻止已删除会话被复用，以及重试文件清理。 */
    @Select("SELECT * FROM chat_session WHERE id = #{id}")
    ChatSession findIncludingDeleted(String id);
}

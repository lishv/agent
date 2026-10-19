package com.xiaoli.aiagent.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @TableName("chat_session")
/**
 * 会话实体类。
 */
public class ChatSession {
    @TableId(type = IdType.INPUT) private String id; // 会话 ID
    private Long userId; // 用户 ID
    private String appId; // 应用 ID
    private String title; // 会话标题
    private String memoryFilePath; // 内存文件路径
    private String transcriptFilePath; // 记录文件路径
    private LocalDateTime createdAt; // 创建时间
    private LocalDateTime updatedAt; // 更新时间
    @TableLogic private Integer isDeleted; // 是否删除
}

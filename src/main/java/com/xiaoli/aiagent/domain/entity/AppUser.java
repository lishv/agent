package com.xiaoli.aiagent.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @TableName("`user`")
public class AppUser {
    /** 用户实体
     * 包含用户的基本信息和状态字段。
     * 主键：id
     * 邮箱：email
     * 密码哈希：passwordHash
     * 显示名称：displayName
     * 认证版本：authVersion
     * 创建时间：createdAt
     * 更新时间：updatedAt
     * 删除状态：isDeleted
     */
    @TableId(type = IdType.ASSIGN_ID) private Long id;
    private String email;
    private String passwordHash;
    private String displayName;
    private Integer authVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic private Integer isDeleted;
}

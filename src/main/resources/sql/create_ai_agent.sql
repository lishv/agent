-- MySQL 8.0+。账号与会话使用 MySQL；PostgreSQL 向量知识库独立配置。
CREATE DATABASE IF NOT EXISTS ai_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ai_agent;

CREATE TABLE IF NOT EXISTS `user` (
    id BIGINT NOT NULL COMMENT 'MyBatis Plus 分配的用户 ID',
    email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL COMMENT '规范化邮箱，唯一',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希，不保存明文',
    display_name VARCHAR(40) NOT NULL,
    auth_version INT NOT NULL DEFAULT 1 COMMENT '密码变更后使旧登录状态失效',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0 正常，1 逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_session (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '前端聊天室 ID',
    user_id BIGINT NOT NULL,
    app_id VARCHAR(10) NOT NULL COMMENT 'love / manus',
    title VARCHAR(100) NOT NULL DEFAULT '新的对话',
    memory_file_path VARCHAR(1024) NOT NULL COMMENT '相对于 chat-memory.base-dir 的模型记忆文件路径',
    transcript_file_path VARCHAR(1024) NOT NULL COMMENT '相对于 chat-memory.base-dir 的页面消息 JSON 路径',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除只更新标记，保留行及会话文件',
    PRIMARY KEY (id),
    KEY idx_chat_user_app_history (user_id, app_id, is_deleted, updated_at),
    CONSTRAINT fk_chat_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT chk_chat_app CHECK (app_id IN ('love', 'manus'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 不创建示例账号，不导入无用户归属的旧会话。正常删除通过后端逻辑删除接口完成。

package com.xiaoli.aiagent.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 会话标题请求；上限与 chat_session.title 的数据库字段保持一致。 */
public record RenameConversationRequest(
        @NotBlank(message = "请输入对话名") @Size(max = 100, message = "对话名最多 100 个字符") String title) {}

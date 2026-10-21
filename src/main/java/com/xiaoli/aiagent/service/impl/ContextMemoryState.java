package com.xiaoli.aiagent.service.impl;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** 单个应用会话的上下文运行状态；包内可见，由上下文服务按原有锁规则读写。 */
final class ContextMemoryState {
    // 同一会话的记忆读写与摘要压缩共用这把锁。
    final ReentrantLock lock = new ReentrantLock();
    // scheduled 表示已安排后台任务，running 表示模型任务正在使用上下文。
    final AtomicBoolean scheduled = new AtomicBoolean();
        volatile boolean running;
        volatile boolean requested;
        volatile boolean deleted;
    // estimatedTokens 包含消息及提示开销，overheadTokens 仅包含系统、工具与控制提示。
    volatile int estimatedTokens;//estimatedTokens 是"当前总成本"（固定成本 + 历史对话）。分开存是为了换工具时只改固定成本的部分，不用重算整个历史
    volatile int overheadTokens;//overheadTokens 是"固定成本"（系统/工具/控制提示，每次都要带）
    volatile String phase = "idle";


    // 最近一次压缩的次数、时间、重试时间与前后占用，供前端展示和重启恢复。
    volatile long count;
    volatile long lastCompactedAt;
    volatile long retryAfter;
    volatile int before;
    volatile int after;
    volatile String message = "";//人类可读中文描述」（比如"正在生成历史摘要..."、"自动压缩完成..."）
}

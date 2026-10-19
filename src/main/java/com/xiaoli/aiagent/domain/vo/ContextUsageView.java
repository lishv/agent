package com.xiaoli.aiagent.domain.vo;

/** 只返回上下文占用与压缩状态，不暴露摘要、工具参数或服务器文件路径。 */
public record ContextUsageView(boolean enabled, boolean estimated, int estimatedTokens,
        int contextWindowTokens, int responseReserveTokens, int inputBudgetTokens, double usagePercent,
        boolean autoEnabled, double thresholdPercent, int retainTurns, String status,
        long lastCompactedAt, int lastBeforeTokens, int lastAfterTokens, long compactionCount, String message) { }

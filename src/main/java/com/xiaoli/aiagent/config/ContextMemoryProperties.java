package com.xiaoli.aiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.HashMap;

/** 应用使用的上下文预算，不代表模型供应商声明的窗口上限。 */
@Data @Component @ConfigurationProperties(prefix = "chat-memory.compaction")
public class ContextMemoryProperties {
    private boolean enabled = true;
    private boolean autoEnabled = true;
    private double threshold = .7;
    private double targetRatio = .4;
    private int retainTurns = 4;
    private int summaryMaxTokens = 2048;
    private Map<String, Budget> apps = new HashMap<>(Map.of("love", new Budget(), "manus", new Budget()));
    @Data public static class Budget {
        private int contextWindowTokens = 32768;
        private int responseReserveTokens = 4096;
        // 输入预算 = 上下文窗口 - 响应保留 Token
        public int inputBudget() { return contextWindowTokens - responseReserveTokens; }
    }
    public Budget budget(String appId) {
        Budget budget = apps.get(appId);
        // 校验预算是否有效
        if (budget == null || budget.contextWindowTokens <= budget.responseReserveTokens || budget.inputBudget() < 1024 || budget.responseReserveTokens < 256
                || !Double.isFinite(threshold) || !Double.isFinite(targetRatio) || threshold <= 0 || threshold >= 1 || targetRatio <= 0 || targetRatio >= threshold
                || retainTurns < 1 || summaryMaxTokens < 128 || summaryMaxTokens >= budget.inputBudget() / 2)
            throw new IllegalStateException("上下文压缩配置无效，请检查预算、保留轮数和阈值");
        return budget;
    }
}

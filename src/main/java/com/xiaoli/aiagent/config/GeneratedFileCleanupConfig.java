package com.xiaoli.aiagent.config;

import com.xiaoli.aiagent.service.GeneratedFileService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 启动后执行一次文件清理，此后按部署配置的间隔持续清理。 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties(StorageProperties.class)
@RequiredArgsConstructor
public class GeneratedFileCleanupConfig {
    private final GeneratedFileService files;

    /** 清理到期工具产物，仅在有文件删除时记录数量。 */
    @Scheduled(
            fixedDelayString = "${app.storage.cleanup-interval:PT5M}",
            initialDelayString = "PT0S")
    public void cleanup() {
        int removed = files.cleanupExpiredFiles();
        if (removed > 0) log.info("已清理 {} 个过期工具文件", removed);
    }
}

package com.xiaoli.aiagent.config;

import com.xiaoli.aiagent.constant.FileConstant;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 工具文件的输出目录、有效期和清理周期；聊天记忆不使用文件有效期。 */
@Data
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {
    private String baseDir = FileConstant.FILE_SAVE_DIR;
    private Duration fileRetention = Duration.ofHours(24);
    private Duration cleanupInterval = Duration.ofMinutes(5);
}

package com.xiaoli.aiagent.chatmemory;

import com.xiaoli.aiagent.util.FilePathUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.file.Path;

/** 两个应用使用独立的文件记忆目录。 */
@Configuration
public class AppChatMemoryConfig {
    private final Path root;

    public AppChatMemoryConfig(@Value("${chat-memory.base-dir:${app.storage.base-dir:./tmp}/chat-memory}") String directory) {
        root = FilePathUtils.chatMemoryRoot(directory);
    }

    @Bean("loveChatMemoryRepository")
    @Primary
    public FileBasedChatMemoryRepository loveChatMemoryRepository() {
        return new FileBasedChatMemoryRepository(root.resolve("love").toString());// 为LoveApp创建文件记忆目录
    }

    @Bean("manusChatMemoryRepository")
    public FileBasedChatMemoryRepository manusChatMemoryRepository() {
        return new FileBasedChatMemoryRepository(root.resolve("manus").toString());// 为ManusApp创建文件记忆目录
    }
}

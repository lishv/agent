package com.xiaoli.aiagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 基于文件持久化的对话记忆仓库
 */
public class FileBasedChatMemoryRepository implements ChatMemoryRepository {

    private static final String FILE_SUFFIX = ".kryo";
    private static final Kryo KRYO = new Kryo();// 初始化Kryo对象,用于序列化和反序列化对象

    static {
        KRYO.setRegistrationRequired(false);// 不需要手动注册
        KRYO.setInstantiatorStrategy(new StdInstantiatorStrategy());// 设置实例化策略
    }

    private final File baseDir;// 文件保存目录

    // 构造对象时，指定文件保存目录
    public FileBasedChatMemoryRepository(String dir) {
        this.baseDir = new File(Objects.requireNonNull(dir, "路径不能为空"));
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IllegalStateException("无法创建对话记忆目录: " + baseDir);
        }
        if (!baseDir.isDirectory()) {
            throw new IllegalArgumentException("对话记忆路径不是目录: " + baseDir);
        }
    }

    /**
     * 获取所有对话id
     *
     * @return 对话id列表
     */
    @Override
    public List<String> findConversationIds() {
        File[] files = baseDir.listFiles(file -> file.isFile() && file.getName().endsWith(FILE_SUFFIX));
        if (files == null) {
            return List.of();
        }

        return Arrays.stream(files)
                .map(File::getName)
                .map(fileName -> fileName.substring(0, fileName.length() - FILE_SUFFIX.length()))
                .toList();
    }

    /**
     * 根据对话id获取对话记录
     *
     * @param conversationId 对话id
     * @return 对话记录；会话不存在时返回空列表
     */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        File file = getConversationFile(conversationId);
        if (!file.exists()) {
            return List.of();
        }
        // 从文件中读取对话记录,并反序列化为ArrayList<Message>
        synchronized (KRYO) {
            try (Input input = new Input(new FileInputStream(file))) {
                return new ArrayList<>(KRYO.readObject(input, ArrayList.class));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("无法读取对话记忆文件: " + file, e);
            }
        }
    }

    /**
     * 保存对话记录
     *
     * @param conversationId 对话id
     * @param messages       对话记录
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        File targetFile = getConversationFile(conversationId);
        File tempFile = new File(baseDir, targetFile.getName() + ".tmp");
        // 使用Kryo序列化对话记录,并写入临时文件
        synchronized (KRYO) {
            try (Output output = new Output(new FileOutputStream(tempFile))) {
                KRYO.writeObject(output, new ArrayList<>(messages));
            } catch (IOException | RuntimeException e) {
                if (!tempFile.delete() && tempFile.exists()) {
                    tempFile.deleteOnExit();
                }
                throw new IllegalStateException("无法保存对话记忆文件: " + targetFile, e);
            }
        }

        try {
            Files.move(tempFile.toPath(), targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackException) {
                throw new IllegalStateException("无法替换对话记忆文件: " + targetFile, fallbackException);
            }
        }
    }

    /**
     * 根据对话id删除对话记录
     *
     * @param conversationId 对话id
     */
    @Override
    public void deleteByConversationId(String conversationId) {
        File file = getConversationFile(conversationId);
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("无法删除对话记忆文件: " + file);
        }
    }

    // 根据对话id获取对话记录文件
    private File getConversationFile(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        if (!conversationId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("无效的对话id: " + conversationId);
        }
        return new File(baseDir, conversationId + FILE_SUFFIX);
    }
}

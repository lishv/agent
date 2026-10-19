package com.xiaoli.aiagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiaoli.aiagent.util.FilePathUtils;
import com.xiaoli.aiagent.domain.entity.ChatSession;
import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.exception.ErrorCode;
import com.xiaoli.aiagent.exception.ThrowUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
/*
            方法	      干什么	                                               生活比喻
            path(row)	算出文件应该在什么位置，并防止越权	                告诉订单放在文件柜的哪一层
            exists(row)	文件有没有	                                    这个顾客有历史订单吗？
            read(row)	读文件里的聊天消息列表	                        翻出顾客之前所有的订单
            write(target安全地把消息安全地把消息写进文件（临时文件+原子替换）	写新订单，保证不写坏旧订单
            delete(transcript)	删除文件	 扔掉顾客的旧订单

 */

/**
 * 页面聊天记录的文件存储；集中处理路径校验、JSON 读写和临时文件替换。
 */
public final class ConversationTranscriptStore {
    private final ObjectMapper json;
    private final Path root;

    /**
     * 使用应用统一的 JSON 配置，并解析配置或默认的聊天记录根目录。
     */
    public ConversationTranscriptStore(ObjectMapper json, String directory) {
        this.json = json;
        root = FilePathUtils.chatMemoryRoot(directory);
    }

    /**
     * 返回会话记录的规范化路径，并拒绝存储根目录以外的路径。
     */
    public Path path(ChatSession row) {
        Path path = root.resolve(row.getTranscriptFilePath()).normalize();
        ThrowUtils.throwIf(!path.startsWith(root), ErrorCode.SYSTEM_ERROR, "会话存储路径配置异常");
        return path;
    }

    /**
     * 检查页面记录文件是否存在，供会话详情区分新记录与缺失的历史文件。
     */
    public boolean exists(ChatSession row) {
        return Files.exists(path(row));
    }

    /**
     * 读取页面消息；新会话尚无文件时返回空列表，读取异常时保留文件并提示重试。
     */
    public List<ObjectNode> read(ChatSession row) {
        Path path = path(row);
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            return new ArrayList<>(
                    json.readValue(
                            Files.readAllBytes(path),
                            json.getTypeFactory()
                                    .constructCollectionType(List.class, ObjectNode.class)));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "对话文件暂时无法读取，请稍后重试", e);
        }
    }

    /**
     * 将消息写入已校验的目标路径；优先原子替换，不支持时退回普通替换。
     */
    public void write(Path target, List<ObjectNode> messages) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".transcript-", ".tmp");
        try {
            Files.write(temp, json.writeValueAsBytes(messages));
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                // 临时文件清理不覆盖原始读写异常。
            }
        }
    }

    /**
     * 删除已校验路径的记录文件，文件不存在时忽略，失败时允许客户端重试清理。
     */
    public void delete(Path transcript) {
        try {
            Files.deleteIfExists(transcript);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "对话已移除，文件清理暂未完成，请重试", e);
        }
    }
}

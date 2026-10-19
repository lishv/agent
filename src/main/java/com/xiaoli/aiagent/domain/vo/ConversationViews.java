package com.xiaoli.aiagent.domain.vo;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
public final class ConversationViews {
    private ConversationViews() { }
    public record Summary(String id, String appId, String title, long createdAt, long updatedAt, boolean running) { }
    public record Detail(String id, String appId, String title, long createdAt, long updatedAt, List<ObjectNode> messages, boolean running) { }
}

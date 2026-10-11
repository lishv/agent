package com.xiaoli.aiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Objects;
import java.util.function.Function;

/**
 * 人机交互工具。
 * 回答由外部提供，当前可以使用示例实现，
 * 后续可以接入接收前端回答的业务实现。
 */
public class AskHumanTool {

    private final Function<String, String> answerProvider;

    public AskHumanTool(Function<String, String> answerProvider) {
        this.answerProvider = Objects.requireNonNull(answerProvider);
    }

    @Tool(
            description = """
                    Ask the user for missing information, preferences or feedback
                    when necessary to complete the task.
                    Use the returned answer in subsequent reasoning.
                    An answer marked as an example is demonstration data,
                    not a real user response.
                    """,
            returnDirect = false
    )
    public HumanReply askHuman(@ToolParam(description = "A clear question to ask the user") String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        // 将问题交给外部回答提供方
        String answer = answerProvider.apply(question);

        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("尚未收到用户回答");
        }
        return new HumanReply(question.trim(), answer.trim());
    }

    /**
     * 交互结果，可以序列化为 JSON。
     */
    public record HumanReply(String question, String answer) { }
}
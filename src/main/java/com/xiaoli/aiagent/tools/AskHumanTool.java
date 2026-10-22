package com.xiaoli.aiagent.tools;
import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.exception.ErrorCode;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Objects;
import java.util.function.Function;

/**
 * 人机交互工具。
 * 回答由当前任务的交互会话提供，等待真实用户回答后返回。
 */
public class AskHumanTool {

    private final Function<String, String> answerProvider;
    //回调函数，interaction::ask
    public AskHumanTool(Function<String, String> answerProvider) {
        this.answerProvider = Objects.requireNonNull(answerProvider);
    }

    @Tool(
            description = """
                    Ask the user for missing information, preferences or feedback
                    when necessary to complete the task.
                    Use the returned answer in subsequent reasoning.
                    This tool pauses execution until the user supplies an actual answer.
                    Ask a specific question based on the current task. Never invent an answer.
                    """,
            returnDirect = false
    )
    public HumanReply askHuman(@ToolParam(description = "A clear question to ask the user") String question) {
        if (question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "question 不能为空");
        }
        // 将问题交给外部回答提供方interaction::ask
        String answer = answerProvider.apply(question);

        if (answer == null || answer.isBlank()) {
            throw new BusinessException(ErrorCode.CONFLICT_ERROR, "尚未收到用户回答");
        }
        return new HumanReply(question.trim(), answer.trim());
    }
    /**
     * 交互结果，可以序列化为 JSON。
     */
    public record HumanReply(String question, String answer) { }
}

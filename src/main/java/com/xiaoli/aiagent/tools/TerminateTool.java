package com.xiaoli.aiagent.tools;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 终止工具（作用是让自主规划智能体能够合理地中断）
 */
public class TerminateTool {

    /** 返回任务结束标记，智能体根据工具调用结束循环；最终答复仍由模型提供。 */
    @Tool(description = """
            End tool execution when the request is met or the assistant cannot proceed further.
            Include the actual final answer to the user in your response text when calling this tool.
            A termination notice alone does not answer the user.
            """)
    public String doTerminate() {
        return "任务结束";
    }
}

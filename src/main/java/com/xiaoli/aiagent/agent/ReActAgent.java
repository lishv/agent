package com.xiaoli.aiagent.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import com.xiaoli.aiagent.agent.model.AgentState;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * ReAct (Reasoning and Acting) 模式的代理抽象类
 * 实现了思考-行动的循环模式
 * 即：先思考当前状态,决定是否需要执行工具调用,如果需要,则调用工具,并根据工具返回的结果,继续思考
 * 再根据思考结果,执行行动,直到完成任务
 */
@EqualsAndHashCode(callSuper = true)// 调用父类的equals方法
@Data
@Slf4j
public abstract class ReActAgent extends BaseAgent {

    /**
     * 处理当前状态并决定下一步行动是否需要执行工具调用
     * 专门用于思考判断是否需要使用工具调用
     * @return 是否需要执行行动，true表示需要执行，false表示不需要执行
     */
    public abstract boolean think();

    /**
     * 执行工具调用,并返回处理结果
     *
     * @return 行动-工具执行结果
     */
    public abstract String act();

    /**
     * 执行单个步骤：思考是否需要工具+行动-工具调用(think+act)
     *
     * @return 本次工具调用的结果
     */
    @Override
    public String step() {
        boolean shouldAct = think();
        if (!shouldAct) {
            setState(AgentState.FINISHED);
            // 用户补充信息后的最终回答也要返回前端，而不是只写入日志。
            if (!getMessageList().isEmpty()
                    && getMessageList().getLast() instanceof AssistantMessage message) {
                return message.getText();
            }
            return "任务处理结束";
        }
        // 异常交给 BaseAgent 结束任务，不能在等待超时后继续反复提问。
        return act();
    }

}

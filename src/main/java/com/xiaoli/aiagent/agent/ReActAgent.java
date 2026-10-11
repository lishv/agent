package com.xiaoli.aiagent.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

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
        try {
            // 先思考
            boolean shouldAct = think();
            if (!shouldAct) {
                return "思考完成 - 无需行动";
            }
            // 再行动
            return act();
        } catch (Exception e) {
            // 记录异常日志
            e.printStackTrace();
            return "步骤执行失败：" + e.getMessage();
        }
    }

}

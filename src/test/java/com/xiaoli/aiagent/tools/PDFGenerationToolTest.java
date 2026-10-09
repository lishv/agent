package com.xiaoli.aiagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PDFGenerationToolTest {

    @Test
    void generatePDF() {
        PDFGenerationTool tool = new PDFGenerationTool();
        String fileName = "项目.pdf";
        String content = "项目https://www.runoob.com/ai-agent/ai-agent-function-calling.html" +
                "\n" +
                "搜索……\n" +
                "首页 HTML JavaScript CSS Vue React Python3 Java C C++ C# AI Go SQL Linux VS Code Bootstrap Git 本地书签 \n" +
                " AI Agent 教程\n" +
                "AI Agent(智能体) 教程\n" +
                "AI Agent 简介\n" +
                "AI Agent 核心组件\n" +
                "AI Agent 术语\n" +
                "AI Agent 工作原理\n" +
                "AI 底层架构\n" +
                "大语言模型基础\n" +
                "大模型多模态\n" +
                "提示词工程\n" +
                "Token (词元)\n" +
                "推理与规划\n" +
                "向量数据库\n" +
                "RAG 与知识检索\n" +
                "Agent 上下文工程\n" +
                "Agent 架构\n" +
                "\n" +
                "智能体工具\n" +
                "第一个 AI Agent\n" +
                "OpenClaw (Clawdbot) 教程\n" +
                "OpenClaw 快速上手\n" +
                "QoderWork 教程\n" +
                "QoderWake 教程\n" +
                "WorkBuddy 入门教程\n" +
                "AI 工作流\n" +
                "CrewAI 构建智能体\n" +
                "LangChain 制作智能体\n" +
                "LangGraph 入门教程\n" +
                "Deep Agents 入门教程\n" +
                "GraphRAG 入门教程\n" +
                "Harness Engineering\n" +
                "Hermes Agent\n" +
                "Loop Engineering\n" +
                "Vibe Coding\n" +
                "Dify 入门教程\n" +
                "n8n 入门教程\n" +
                "Pi Coding Agent\n" +
                "AI 3D (Blender)\n" +
                "\n" +
                "Python 实现智能体\n" +
                "Python 实现 AI Agent\n" +
                "工具调用\n" +
                "记忆系统\n" +
                "Python 智能体环境配置\n" +
                "AI Agent 问答实例\n" +
                "推理与规划\n" +
                "RAG 与知识检索\n" +
                "多智能体系统\n" +
                "工具与外部集成\n" +
                "多模态 Agent\n" +
                "评估、安全与对齐\n" +
                "生产部署与工程化\n" +
                "垂直应用场景\n" +
                "Hugging Face Transformers\n" +
                " Python 实现 AI Agent记忆系统 \n" +
                "深入探索\n" +
                "机器学习与人工智能\n" +
                "语言资源\n" +
                "数学\n" +
                "教育资源\n" +
                "教育\n" +
                "心理学\n" +
                "建筑电气工具\n" +
                "字典与百科全书\n" +
                "编程\n" +
                "教学与教室资源\n" +
                "工具调用（Function Calling）\n" +
                "Function Calling（函数调用）是让 LLM 能够使用外部工具的核心机制。\n" +
                "\n" +
                "Function Calling（函数调用）允许模型决定何时调用工具、调用哪个工具，以及传递什么参数。\n" +
                "\n" +
                "为什么需要 Function Calling？\n" +
                "想象一下，LLM 是一个聪明但手无寸铁的顾问。它知道很多知识，但不能：\n" +
                "\n" +
                "获取实时信息（如最新天气、股票价格）\n" +
                "执行计算（如复杂的数学运算）\n" +
                "操作外部系统（如发送邮件、读写文件）\n" +
                "Function Calling 为 LLM 提供了双手，让它能够突破自身限制，执行实际任务。\n" +
                "\n" +
                "Function Calling 的工作原理";

        String result = tool.generatePDF(fileName, content);
        assertNotNull(result);
    }
}
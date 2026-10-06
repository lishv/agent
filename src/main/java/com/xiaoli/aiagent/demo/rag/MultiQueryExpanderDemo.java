package com.xiaoli.aiagent.demo.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 查询扩展器 Demo
 */
@Component
public class MultiQueryExpanderDemo {
/*
假设用户输入原始问题："谁是程序员鱼皮啊？"

AI 可能扩展出（numberOfQueries(3)）：

#	扩展后的查询
1	程序员鱼皮是谁？他有什么代表作品？
2	编程导航 codefather.cn 的创始人是谁？
3	关于程序员鱼皮的个人介绍和项目
然后根据扩展后的查询，从数据库中检索出相关的文档。
 */
    private final ChatClient.Builder chatClientBuilder;

    public MultiQueryExpanderDemo(ChatModel ollamaChatModel) {
        this.chatClientBuilder = ChatClient.builder(ollamaChatModel);
    }

    public List<Query> expand(String query) {
        MultiQueryExpander queryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(chatClientBuilder)
                .numberOfQueries(3)
                .build();
        List<Query> queries = queryExpander.expand(new Query("谁是程序员鱼皮啊？"));
        return queries;
    }
}

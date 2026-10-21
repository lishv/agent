package com.xiaoli.aiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 自定义基于 Token 的切词器
 */
@Component
class MyTokenTextSplitter {
    // 默认切词器
    public List<Document> splitDocuments(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter();
        return splitter.apply(documents);
    }
    // 自定义切词器
    public List<Document> splitCustomized(List<Document> documents) {
        // 自定义切词器参数
        TokenTextSplitter splitter = new TokenTextSplitter(200, 100, 10, 5000, true);
        return splitter.apply(documents);
    }
    /*
    现在是基于Markdown的切词器
     */
    /*
    参数	                默认值	    含义
    defaultChunkSize	    800	    每块 800 token
    minChunkSizeChars	    350	    每块最少 350 字符
    minChunkLengthToEmbed	5	每块最少 5 个 token（太短的丢弃）
    maxNumChunks	        10000	最多切 10000 块
    keepSeparator	        true	保留分隔符
     */
}

package com.xiaoli.aiagent.rag;

import com.xiaoli.aiagent.demo.rag.MultiQueryExpanderDemo;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 查询扩展器 Demo 测试, 测试查询扩展器是否能够正确扩展查询
 */
@SpringBootTest
class MultiQueryExpanderDemoTest {

    @Resource
    private MultiQueryExpanderDemo multiQueryExpanderDemo;

    @Test
    void expand() {
        List<Query> queries = multiQueryExpanderDemo.expand("啥是程序员鱼皮啊啊啊啊啊啊？！请回答我哈哈哈哈");
        Assertions.assertNotNull(queries);
    }
}

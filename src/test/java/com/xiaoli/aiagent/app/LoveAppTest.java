package com.xiaoli.aiagent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.content.Media;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;

import org.springframework.util.MimeTypeUtils;
import java.util.List;
import java.util.UUID;

@SpringBootTest
class LoveAppTest {

    @Resource
    private LoveApp loveApp;
    PromptTemplate promptTemplate;
    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好，我是程序员鱼皮";
        String answer = loveApp.doChat(message, chatId);
        // 第二轮
        message = "我想让另一半（编程导航）更爱我";
        answer = loveApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第三轮
        message = "我的另一半叫什么来着？刚跟你说过，帮我回忆一下";
        answer = loveApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();
        String message = "你好，我是程序员鱼皮，我想让另一半（编程导航）更爱我，但我不知道该怎么做";
        LoveApp.LoveReport loveReport = loveApp.doChatWithReport(message, chatId);
        Assertions.assertNotNull(loveReport);
    }
    @Test
    void doChatWithMultiModal() {
        String chatId = UUID.randomUUID().toString();

        org.springframework.core.io.Resource img1 = new FileSystemResource("D:\\Pictures\\壁纸\\174003-hui_kai_xuan-pokmon_qu-pi_ka_qiu-xiao_lu-hu_xu-1920x1080.jpg");
        Assumptions.assumeTrue(img1.exists(), "测试数据1不存在");
        org.springframework.core.io.Resource img2 = new FileSystemResource("D:\\Pictures\\壁纸\\21586ed7881711ebb6edd017c2d2eca2.jpg");
        Assumptions.assumeTrue(img2.exists(), "测试数据2不存在");



        List<Media> mediaList = List.of(
                new Media(MimeTypeUtils.IMAGE_JPEG, img1),
                new Media(MimeTypeUtils.ALL, img2)
        );

        String result = loveApp.doChatWithMedia(
                "请描述所给数据的内容，并分析它可能表达的情绪。",
                chatId,
                mediaList
        );
        Assertions.assertNotNull(result);
        System.out.println("多模态模型返回结果：" + result);
    }

    @Test
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();
        String message = "我已经结婚了，但是婚后感觉对方不像以前那么关心自己,怎么办？";
        String answer = loveApp.doChatWithRag(message, chatId);
        Assertions.assertNotNull(answer);
    }

//    @Test
//    void doChatWithTools() {
//        // 测试联网搜索问题的答案
//        testMessage("周末想带女朋友去上海约会，推荐几个适合情侣的小众打卡地？");
//
//        // 测试网页抓取：恋爱案例分析
//        testMessage("最近和对象吵架了，看看编程导航网站（codefather.cn）的其他情侣是怎么解决矛盾的？");
//
//        // 测试资源下载：图片下载
//        testMessage("直接下载一张适合做手机壁纸的星空情侣图片为文件");
//
//        // 测试终端操作：执行代码
//        testMessage("执行 Python3 脚本来生成数据分析报告");
//
//        // 测试文件操作：保存用户档案
//        testMessage("保存我的恋爱档案为文件");
//
//        // 测试 PDF 生成
//        testMessage("生成一份‘七夕约会计划’PDF，包含餐厅预订、活动流程和礼物清单");
//    }

//    private void testMessage(String message) {
//        String chatId = UUID.randomUUID().toString();
//        String answer = loveApp.doChatWithTools(message, chatId);
//        Assertions.assertNotNull(answer);
//    }

//    @Test
//    void doChatWithMcp() {
//        String chatId = UUID.randomUUID().toString();
//        // 测试地图 MCP
////        String message = "我的另一半居住在上海静安区，请帮我找到 5 公里内合适的约会地点";
////        String answer =  loveApp.doChatWithMcp(message, chatId);
////        Assertions.assertNotNull(answer);
//        // 测试图片搜索 MCP
//        String message = "帮我搜索一些哄另一半开心的图片";
//        String answer =  loveApp.doChatWithMcp(message, chatId);
//        Assertions.assertNotNull(answer);
//    }
}

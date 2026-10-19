package com.xiaoli.aiagent.tools;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 使用本地 HTML 验证正文提取，不访问外网。 */
class WebPageContentTest {
    @Test void extractsTitleUrlAndMainTextWithoutScriptOrNavigation() {
        var document=Jsoup.parse("<html><head><title>学习 &amp; 实践</title><style>body{color:red}</style></head>"
                +"<body><nav>导航菜单</nav><main><h1>Java 学习</h1><p>先学习基础语法。</p><p>再完成一个项目。</p>"
                +"<script>alert(1)</script></main></body></html>","https://example.test/guide");
        String text=new WebScrapingTool().readableContent(document);
        assertTrue(text.contains("学习 & 实践")); assertTrue(text.contains("https://example.test/guide"));
        assertTrue(text.contains("Java 学习\n")); assertTrue(text.contains("先学习基础语法。\n"));
        assertTrue(text.contains("再完成一个项目。"));
        assertFalse(text.contains("alert(1)")); assertFalse(text.contains("导航菜单")); assertFalse(text.contains("<html>"));
    }
    @Test void explainsWhenClientRenderedPageHasNoReadableBody() {
        String text=new WebScrapingTool().readableContent(Jsoup.parse("<html><head><title>应用</title></head>"
                +"<body><div id='app'></div><script src='/app.js'></script></body></html>","https://example.test/"));
        assertTrue(text.contains("页面没有可读正文")); assertTrue(text.contains("当前抓取无法确认页面数据"));
    }
}

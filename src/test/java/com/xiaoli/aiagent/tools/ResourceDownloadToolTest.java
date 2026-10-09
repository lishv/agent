package com.xiaoli.aiagent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceDownloadToolTest {

    @Test
    public void testDownloadResource() {
        ResourceDownloadTool tool = new ResourceDownloadTool();
        String url = "https://img.huilingyun.asia/public/2084640382689312770/2026-09-20-5aZLaYiqnXU2Va0e.webp";
        String fileName = "山川.png";
        String result = tool.downloadResource(url, fileName);
        assertNotNull(result);
    }
}
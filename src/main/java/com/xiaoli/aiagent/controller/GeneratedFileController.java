package com.xiaoli.aiagent.controller;

import com.xiaoli.aiagent.service.AccountService;
import com.xiaoli.aiagent.service.GeneratedFileService;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 登录用户下载本人任务生成的文件；输出目录不会整体映射成公开静态资源。 */
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class GeneratedFileController {
    private final GeneratedFileService files;
    private final AccountService accounts;

    /** 校验归属和有效期后流式返回附件，支持中文文件名并禁止浏览器缓存。 */
    @GetMapping("/{category}/{runId}")
    public ResponseEntity<Resource> download(
            @PathVariable String category,
            @PathVariable String runId,
            @RequestParam String name,
            HttpServletRequest request)
            throws IOException {
        var file =
                files.requireDownload(accounts.requireUser(request).getId(), category, runId, name);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.getFileName().toString(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file));
    }
}

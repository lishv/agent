package com.xiaoli.aiagent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供无需登录的应用存活检查。 */
@RestController
@RequestMapping("/health")
public class HealthController {

    /** 返回固定字符串 ok，表示 HTTP 服务能够正常处理请求。 */
    @GetMapping
    public String healthCheck() {
        return "ok";
    }
}

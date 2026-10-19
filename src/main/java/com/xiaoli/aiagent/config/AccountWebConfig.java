package com.xiaoli.aiagent.config;

import com.xiaoli.aiagent.exception.ErrorCode;
import com.xiaoli.aiagent.exception.ThrowUtils;
import com.xiaoli.aiagent.service.AccountService;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Configuration @RequiredArgsConstructor
public class AccountWebConfig implements WebMvcConfigurer {
    private final AccountService accounts;
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accountInterceptor()).addPathPatterns("/ai/**", "/chats/**", "/auth/**", "/files/**");
    }
    @Bean public HandlerInterceptor accountInterceptor() {
        return new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("Cache-Control", "no-store");
                if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) return true;
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod()) || path.startsWith("/ai/") && !request.getMethod().equals("OPTIONS")) {
                    var session = request.getSession(false);
                    Object token = session == null ? null : session.getAttribute("csrfToken");
                    String supplied = request.getHeader("X-CSRF-TOKEN");
                    ThrowUtils.throwIf(!(token instanceof String expected) || supplied == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)), ErrorCode.FORBIDDEN_ERROR, "登录状态需要刷新，请重试");
                }
                if (path.startsWith("/ai/") || path.startsWith("/chats") || path.startsWith("/files/") || Set.of("/auth/password", "/auth/nickname").contains(path)) {
                    var user = accounts.requireUser(request);
                    String expected = request.getHeader("X-Account-ID");
                    ThrowUtils.throwIf(expected != null && !expected.equals(user.getId().toString()), ErrorCode.ACCOUNT_CHANGED);
                    request.setAttribute("authenticatedUser", user);
                }
                return true;
            }
        };
    }
}

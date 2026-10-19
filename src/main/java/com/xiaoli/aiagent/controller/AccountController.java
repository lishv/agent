package com.xiaoli.aiagent.controller;

import com.xiaoli.aiagent.domain.dto.AccountRequests.*;
import com.xiaoli.aiagent.domain.entity.AppUser;
import com.xiaoli.aiagent.domain.vo.AccountViews.SessionView;
import com.xiaoli.aiagent.domain.vo.AccountViews.UserView;
import com.xiaoli.aiagent.service.AccountService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 账号接口：登录、注册、资料维护和密码管理。 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AccountController {
    private final AccountService accounts;

    /** 返回当前用户与 CSRF 令牌；未登录时用户信息为空。 */
    @GetMapping("/session")
    public SessionView session(HttpServletRequest request) {
        return sessionView(accounts.current(request), request);
    }

    /** 校验邮箱密码，建立登录态并返回新的会话信息。 */
    @PostMapping("/login")
    public SessionView login(@Valid @RequestBody Login body, HttpServletRequest request) {
        return sessionView(accounts.login(body.email(), body.password(), request), request);
    }

    /** 消费邮箱验证码并登录已注册账号。 */
    @PostMapping("/code-login")
    public SessionView codeLogin(@Valid @RequestBody CodeLogin body, HttpServletRequest request) {
        return sessionView(accounts.codeLogin(body.email(), body.code(), request), request);
    }

    /** 修改当前用户的昵称，返回不含敏感字段的用户信息。 */
    @PostMapping("/nickname")
    public UserView nickname(@Valid @RequestBody Nickname body, HttpServletRequest request) {
        return accounts.view(
                accounts.updateNickname(accounts.requireUser(request), body.displayName()));
    }

    /** 校验注册信息及验证码，创建账号并自动登录。 */
    @PostMapping("/register")
    public SessionView register(@Valid @RequestBody Register body, HttpServletRequest request) {
        return sessionView(
                accounts.register(
                        body.email(),
                        body.code(),
                        body.password(),
                        body.confirmPassword(),
                        body.displayName(),
                        request),
                request);
    }

    /** 按注册、登录或重置密码用途发送验证码，使用请求 IP 参与限流。 */
    @PostMapping("/email-code")
    public Map<String, Boolean> code(@Valid @RequestBody Code body, HttpServletRequest request) {
        accounts.sendCode(body.email(), body.purpose(), request.getRemoteAddr());
        return Map.of("sent", true);
    }

    /** 使用邮箱验证码重置密码，使该账号已有登录态失效。 */
    @PostMapping("/reset-password")
    public Map<String, Boolean> reset(@Valid @RequestBody Reset body) {
        accounts.reset(body.email(), body.code(), body.password(), body.confirmPassword());
        return Map.of("changed", true);
    }

    /** 校验当前密码后修改密码，并立即注销当前会话。 */
    @PostMapping("/password")
    public Map<String, Boolean> change(
            @Valid @RequestBody Change body, HttpServletRequest request) {
        accounts.change(
                accounts.requireUser(request),
                body.currentPassword(),
                body.password(),
                body.confirmPassword());
        invalidateSession(request);
        return Map.of("changed", true);
    }

    /** 注销当前浏览器会话；尚未建立会话时也返回成功。 */
    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest request) {
        invalidateSession(request);
        return Map.of("loggedOut", true);
    }

    /** 组合安全的用户信息和 CSRF 令牌，兼容匿名会话。 */
    private SessionView sessionView(AppUser user, HttpServletRequest request) {
        return new SessionView(user == null ? null : accounts.view(user), accounts.csrf(request));
    }

    /** 仅使已有 Session 失效，避免为注销操作创建新 Session。 */
    private void invalidateSession(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }
}

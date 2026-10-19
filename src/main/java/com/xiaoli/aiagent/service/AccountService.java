package com.xiaoli.aiagent.service;

import com.xiaoli.aiagent.domain.entity.AppUser;
import com.xiaoli.aiagent.domain.vo.AccountViews.UserView;

import jakarta.servlet.http.HttpServletRequest;

/** 账号业务契约：身份校验、邮箱登录、资料维护和密码更新。 */
public interface AccountService {
    /** 将用户实体转换为仅包含 ID、邮箱和昵称的安全视图。 */
    UserView view(AppUser user);

    /** 查询当前登录用户；未登录或认证版本过期时返回 null。 */
    AppUser current(HttpServletRequest request);

    /** 复用请求已验证的用户或恢复登录态，未登录时抛出业务异常。 */
    AppUser requireUser(HttpServletRequest request);

    /** 获取 Session 的 CSRF 令牌，尚未存在时生成并保存。 */
    String csrf(HttpServletRequest request);

    /** 执行登录限流和邮箱密码校验，通过后轮换 Session ID 与 CSRF 令牌。 */
    AppUser login(String email, String password, HttpServletRequest request);

    /** 消费登录验证码并登录已有账号，不直接注册新邮箱。 */
    AppUser codeLogin(String email, String code, HttpServletRequest request);

    /** 规范化邮箱后按用途与客户端 IP 发送验证码。 */
    void sendCode(String email, String purpose, String remoteAddress);

    /** 更新昵称并同步传入实体；账号认证版本已变化时拒绝修改。 */
    AppUser updateNickname(AppUser user, String name);

    /** 校验注册信息及验证码，创建账号并自动登录；重复邮箱返回冲突。 */
    AppUser register(
            String email,
            String code,
            String password,
            String confirm,
            String name,
            HttpServletRequest request);

    /** 消费重置验证码，更新密码并递增认证版本，使旧登录态失效。 */
    void reset(String email, String code, String password, String confirm);

    /** 校验当前密码后更新密码，并递增认证版本，使旧登录态失效。 */
    void change(AppUser user, String currentPassword, String password, String confirm);
}

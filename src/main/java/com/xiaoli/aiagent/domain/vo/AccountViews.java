package com.xiaoli.aiagent.domain.vo;
public final class AccountViews {
    private AccountViews() { }
    /** 大整数 ID 输出为字符串；密码、认证版本和数据库对象不返回前端。 */
    public record UserView(String id, String email, String displayName) { }
    public record SessionView(UserView user, String csrfToken) { }
}

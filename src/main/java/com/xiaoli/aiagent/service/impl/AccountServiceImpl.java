package com.xiaoli.aiagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaoli.aiagent.domain.entity.AppUser;
import com.xiaoli.aiagent.domain.vo.AccountViews.UserView;
import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.exception.ErrorCode;
import com.xiaoli.aiagent.exception.ThrowUtils;
import com.xiaoli.aiagent.manager.EmailCodeManager;
import com.xiaoli.aiagent.mapper.UserMapper;
import com.xiaoli.aiagent.service.AccountService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/** 复用 MyBatis-Plus 用户操作，集中处理账号校验、登录态和认证版本更新。 */
@Service
public class AccountServiceImpl extends ServiceImpl<UserMapper, AppUser> implements AccountService {
    private final EmailCodeManager codes;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    // 未注册邮箱也执行 BCrypt 校验，避免通过登录耗时推测账号是否存在。
    private final String dummyHash = passwords.encode("unavailable-account-password");

    /** 绑定 MyBatis-Plus 用户 Mapper 与验证码管理器，兼容 Spring 注入和直接构造。 */
    public AccountServiceImpl(UserMapper users, EmailCodeManager codes) {
        this.baseMapper = users;
        this.codes = codes;
    }

    /** 返回仅包含用户 ID、邮箱和昵称的视图，避免输出密码等敏感字段。 */
    @Override
    public UserView view(AppUser user) {
        return new UserView(user.getId().toString(), user.getEmail(), user.getDisplayName());
    }

    /** 从已有 Session 恢复用户；账号不存在或认证版本过期时注销 Session 并返回 null。 */
    @Override
    public AppUser current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute("userId") instanceof Long id)) return null;
        AppUser user = getById(id);
        if (user == null || !user.getAuthVersion().equals(session.getAttribute("authVersion"))) {
            session.invalidate();
            return null;
        }
        return user;
    }

    /** 优先复用请求中已验证的用户；否则恢复登录态，未登录时抛出异常。 */
    @Override
    public AppUser requireUser(HttpServletRequest request) {
        Object cached = request.getAttribute("authenticatedUser");
        // 优先复用请求中已验证的用户,如未验证则恢复登录态进行登录,未登录则抛出异常
        AppUser user = cached instanceof AppUser authenticated ? authenticated : current(request);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        return user;
    }

    /** 复用 Session 中的 CSRF 令牌；不存在时生成 32 字节安全随机令牌并保存。 */
    @Override
    public String csrf(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String token = (String) session.getAttribute("csrfToken");
        if (token == null) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute("csrfToken", token);
        }
        return token;
    }

    /** 轮换 Session ID，记录用户及认证版本，并更新 CSRF 令牌。 */
    private void signIn(AppUser user, HttpServletRequest request) {
        HttpSession session = request.getSession();
        request.changeSessionId();
        session.setAttribute("userId", user.getId());
        session.setAttribute("authVersion", user.getAuthVersion());
        session.removeAttribute("csrfToken");
        csrf(request);
    }

    /** 执行登录限流和 BCrypt 校验，通过后建立登录态；未知邮箱同样执行密码校验。 */
    @Override
    public AppUser login(String email, String password, HttpServletRequest request) {
        email = prepareLogin(email, request);
        AppUser user = findByEmail(email);
        String candidate =
                password == null
                                || password.length() > 64
                                || password.getBytes(StandardCharsets.UTF_8).length > 72
                        ? ""
                        : password;
        boolean matched =
                passwords.matches(candidate, user == null ? dummyHash : user.getPasswordHash());
        ThrowUtils.throwIf(user == null || !matched, ErrorCode.NOT_LOGIN_ERROR, "邮箱或密码不正确");
        signIn(user, request);
        return user;
    }

    /** 执行登录限流并消费一次验证码，仅允许已注册账号登录。 */
    @Override
    public AppUser codeLogin(String email, String code, HttpServletRequest request) {
        email = prepareLogin(email, request);
        codes.consume(email, "login", code);
        AppUser user = findByEmail(email);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_LOGIN_ERROR, "该邮箱尚未注册，请先注册账号");
        signIn(user, request);
        return user;
    }

    /** 规范化邮箱后委托验证码管理器，按用途与客户端 IP 发送验证码。 */
    @Override
    public void sendCode(String email, String purpose, String remoteAddress) {
        codes.send(EmailCodeManager.normalizeEmail(email), purpose, remoteAddress);
    }

    /** 统一规范化邮箱，并按 IP 与邮箱分别执行登录频率限制。 */
    private String prepareLogin(String email, HttpServletRequest request) {
        String normalized = EmailCodeManager.normalizeEmail(email);
        codes.limit("login-ip:" + request.getRemoteAddr(), 40, 900);
        codes.limit("login-email:" + normalized, 10, 900);
        return normalized;
    }

    /** 使用 MyBatis-Plus 查询未删除的邮箱账号，不存在时返回 null。 */
    private AppUser findByEmail(String email) {
        return lambdaQuery().eq(AppUser::getEmail, email).one();
    }

    /** 校验昵称并按认证版本更新数据库，成功后同步传入的用户实体。 */
    @Transactional
    @Override
    public AppUser updateNickname(AppUser user, String name) {
        String displayName = validateNickname(name);
        LocalDateTime updatedAt = now();
        boolean updated =
                lambdaUpdate()
                        .eq(AppUser::getId, user.getId())
                        .eq(AppUser::getAuthVersion, user.getAuthVersion())
                        .set(AppUser::getDisplayName, displayName)
                        .set(AppUser::getUpdatedAt, updatedAt)
                        .update();
        ThrowUtils.throwIf(!updated, ErrorCode.CONFLICT_ERROR, "账号状态已改变，请重新登录");
        user.setDisplayName(displayName);
        user.setUpdatedAt(updatedAt);
        return user;
    }

    /** 校验注册信息、消费验证码并保存密码哈希，处理重复邮箱后自动登录。 */
    @Transactional
    @Override
    public AppUser register(
            String email,
            String code,
            String password,
            String confirm,
            String name,
            HttpServletRequest request) {
        email = EmailCodeManager.normalizeEmail(email);
        validatePassword(password, confirm);
        String displayName = validateNickname(name);
        ThrowUtils.throwIf(
                lambdaQuery().eq(AppUser::getEmail, email).exists(),
                ErrorCode.CONFLICT_ERROR,
                "该邮箱已注册，请登录");
        codes.consume(email, "register", code);
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash(passwords.encode(password));
        user.setDisplayName(displayName);
        user.setAuthVersion(1);
        user.setIsDeleted(0);
        user.setCreatedAt(now());
        user.setUpdatedAt(now());
        try {
            save(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT_ERROR, "该邮箱已注册，请登录");
        }
        signIn(user, request);
        return user;
    }

    /** 校验新密码、消费重置验证码，再更新账号密码及认证版本。 */
    @Transactional
    @Override
    public void reset(String email, String code, String password, String confirm) {
        email = EmailCodeManager.normalizeEmail(email);
        validatePassword(password, confirm);
        AppUser user = findByEmail(email);
        codes.consume(email, "reset_password", code);
        ThrowUtils.throwIf(user == null, ErrorCode.PARAMS_ERROR, "无法重置该账号密码");
        updatePassword(user, password);
    }

    /** 校验新密码与当前密码，随后复用密码更新逻辑撤销旧登录态。 */
    @Transactional
    @Override
    public void change(AppUser user, String currentPassword, String password, String confirm) {
        validatePassword(password, confirm);
        ThrowUtils.throwIf(
                currentPassword == null
                        || !passwords.matches(currentPassword, user.getPasswordHash()),
                ErrorCode.PARAMS_ERROR,
                "当前密码不正确");
        updatePassword(user, password);
    }

    /** 按认证版本执行条件更新，同时递增版本，使该账号已有登录态失效。 */
    private void updatePassword(AppUser user, String password) {
        boolean updated =
                lambdaUpdate()
                        .eq(AppUser::getId, user.getId())
                        .eq(AppUser::getAuthVersion, user.getAuthVersion())
                        .set(AppUser::getPasswordHash, passwords.encode(password))
                        .set(AppUser::getAuthVersion, user.getAuthVersion() + 1)
                        .set(AppUser::getUpdatedAt, now())
                        .update();
        ThrowUtils.throwIf(!updated, ErrorCode.CONFLICT_ERROR, "账号状态已改变，请重新登录");
    }

    /** 检查密码字符数、BCrypt 的 72 字节限制以及两次输入是否一致。 */
    private static void validatePassword(String password, String confirm) {
        ThrowUtils.throwIf(
                password == null
                        || password.length() < 8
                        || password.length() > 64
                        || password.getBytes(StandardCharsets.UTF_8).length > 72,
                ErrorCode.PARAMS_ERROR,
                "密码需为 8 至 64 位，过长的中文密码请适当缩短");
        ThrowUtils.throwIf(!password.equals(confirm), ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
    }

    /** 移除昵称首尾 Unicode 空白，并校验长度为 1 至 40 个字符。 */
    private static String validateNickname(String name) {
        String value = name == null ? "" : name.strip();
        ThrowUtils.throwIf(
                value.isBlank() || value.length() > 40, ErrorCode.PARAMS_ERROR, "昵称需为 1 至 40 个字符");
        return value;
    }

    /** 使用 UTC 生成数据库时间，避免依赖服务器本地时区。 */
    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

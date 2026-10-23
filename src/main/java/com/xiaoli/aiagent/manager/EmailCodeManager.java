package com.xiaoli.aiagent.manager;

import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.exception.ErrorCode;
import com.xiaoli.aiagent.exception.ThrowUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component @RequiredArgsConstructor
public class EmailCodeManager {
    private final StringRedisTemplate redis;
    private final JavaMailSender mail;
    @Value("${spring.mail.username:}") private String fromEmail;
    private static final ClassPathResource MAIL_TEMPLATE = new ClassPathResource("mail/verification-code.html");
    private static final ClassPathResource BRAND_LOGO = new ClassPathResource("mail/huiling-logo.png");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
        local n = redis.call('INCR', KEYS[1])
        if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
        return n <= tonumber(ARGV[1]) and 1 or 0
        """, Long.class);
    private static final DefaultRedisScript<Long> VERIFY = new DefaultRedisScript<>("""
        local code = redis.call('GET', KEYS[1])
        if not code then return 0 end
        if tonumber(redis.call('GET', KEYS[2]) or '0') >= 5 then return 0 end
        if code ~= ARGV[1] then
          local n = redis.call('INCR', KEYS[2])
          if n == 1 then redis.call('EXPIRE', KEYS[2], 300) end
          if n >= 5 then redis.call('DEL', KEYS[1]) end
          return 0
        end
        redis.call('DEL', KEYS[1], KEYS[2])
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> ROLLBACK = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('DEL', KEYS[1]) end
        if redis.call('GET', KEYS[2]) == ARGV[2] then redis.call('DEL', KEYS[2]) end
        return 1
        """, Long.class);

    public static String normalizeEmail(String email) {
        String value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        ThrowUtils.throwIf(value.length() > 254 || !value.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+"), ErrorCode.PARAMS_ERROR, "请输入有效邮箱");
        return value;
    }
    public void limit(String key, int count, long seconds) {
        ThrowUtils.throwIf(!Long.valueOf(1).equals(redis.execute(LIMIT, List.of("huiling:limit:" + key), String.valueOf(count), String.valueOf(seconds))), ErrorCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后重试");
    }
    public void send(String email, String purpose, String ip) {
        ThrowUtils.throwIf(!List.of("register", "reset_password", "login").contains(purpose), ErrorCode.PARAMS_ERROR, "验证码用途不正确");
        ThrowUtils.throwIf(fromEmail.isBlank(), ErrorCode.SERVICE_UNAVAILABLE, "邮件服务尚未配置");
        limit("mail-ip:" + ip, 20, 900);
        limit("mail-email:" + email, 10, 3600);
        String suffix = purpose + ":" + email;
        String cooldown = "huiling:code:cooldown:" + suffix;
        String key = "huiling:code:" + suffix;
        String reservation = UUID.randomUUID().toString();
        ThrowUtils.throwIf(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(cooldown, reservation, Duration.ofSeconds(60))), ErrorCode.TOO_MANY_REQUESTS, "请等待 60 秒后再发送验证码");
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String digest = digest(email, purpose, code);
        try {
            redis.opsForValue().set(key, digest, Duration.ofMinutes(5));
            redis.delete("huiling:code:attempts:" + suffix);
            var message = mail.createMimeMessage();
            var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_RELATED, "UTF-8");
            helper.setFrom(fromEmail, "绘灵 AI"); helper.setTo(email);
            String title = switch (purpose) {
                case "register" -> "注册验证码";
                case "login" -> "登录验证码";
                default -> "找回密码验证码";
            };
            helper.setSubject("【绘灵 AI】" + title);
            String html = MAIL_TEMPLATE.getContentAsString(StandardCharsets.UTF_8)
                    .replace("{{title}}", title).replace("{{code}}", code);
            helper.setText("绘灵 AI\n您的" + title + "为：" + code
                    + "\n5 分钟内有效。若非本人操作，请忽略此邮件。", html);
            // Logo 随邮件内嵌，资源从 JAR 读取，不依赖网站地址或服务器本地绝对路径。
            helper.addInline("huiling-logo", BRAND_LOGO, "image/png");
            mail.send(message);
        } catch (Exception e) {
            redis.execute(ROLLBACK, List.of(cooldown, key), reservation, digest);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "邮件发送失败，请稍后重试", e);
        }
    }
    public void consume(String email, String purpose, String code) {
        ThrowUtils.throwIf(code == null || !code.matches("\\d{6}"), ErrorCode.PARAMS_ERROR, "请输入六位验证码");
        String suffix = purpose + ":" + email;
        ThrowUtils.throwIf(!Long.valueOf(1).equals(redis.execute(VERIFY, List.of("huiling:code:" + suffix, "huiling:code:attempts:" + suffix), digest(email, purpose, code))), ErrorCode.PARAMS_ERROR, "验证码错误或已过期，请重新获取");
    }
    private static String digest(String email, String purpose, String code) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((purpose + ":" + email + ":" + code).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new BusinessException(ErrorCode.SYSTEM_ERROR, "验证码处理失败，请稍后重试", e); }
    }
}

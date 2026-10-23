package com.xiaoli.aiagent.account;

import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.manager.EmailCodeManager;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 直接校验实际 MIME 邮件；不连接 SMTP，也不向真实收件人发送邮件。 */
class EmailCodeManagerTest {
    private StringRedisTemplate redis;
    private JavaMailSender mail;
    private EmailCodeManager manager;
    private MimeMessage message;

    /** 隔离验证码存储和传输，只保留真实邮件构建与编码。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(1L);
        when(values.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);
        mail = mock(JavaMailSender.class);
        message = new MimeMessage(Session.getInstance(new Properties()));
        when(mail.createMimeMessage()).thenReturn(message);
        manager = new EmailCodeManager(redis, mail);
        ReflectionTestUtils.setField(manager, "fromEmail", "sender@example.test");
    }

    /** 展示品牌名称，同时保留协议要求的真实发件邮箱和正确收件人。 */
    @Test
    void usesBrandDisplayNameAndAuthenticatedSenderAddress() throws Exception {
        manager.send("recipient@example.test", "register", "127.0.0.1");
        InternetAddress sender = (InternetAddress) message.getFrom()[0];
        assertEquals("绘灵 AI", sender.getPersonal());
        assertEquals("sender@example.test", sender.getAddress());
        assertEquals("recipient@example.test", ((InternetAddress) message.getAllRecipients()[0]).getAddress());
        assertFalse(MailTestMessages.html(message).contains("sender@example.test"));
        verify(mail).send(message);
    }

    /** 按真实邮件传输格式重新解析，验证 logo 随邮件发送且能正常解码。 */
    @Test
    void embedsPackagedBrandLogoWithoutExternalImageRequests() throws Exception {
        manager.send("recipient@example.test", "register", "127.0.0.1");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        message.writeTo(bytes);
        MimeMessage delivered = new MimeMessage(message.getSession(), new ByteArrayInputStream(bytes.toByteArray()));
        String html = MailTestMessages.html(delivered);
        assertTrue(html.contains("src=\"cid:huiling-logo\""));
        assertFalse(html.contains("src=\"http"));
        Part logo = MailTestMessages.find(delivered, "image/png");
        assertNotNull(logo, "邮件应携带品牌 logo 图片");
        assertEquals(Part.INLINE, logo.getDisposition());
        assertArrayEquals(new String[] { "<huiling-logo>" }, logo.getHeader("Content-ID"));
        try (var input = logo.getInputStream()) {
            var image = ImageIO.read(input);
            assertNotNull(image);
            assertEquals(256, image.getWidth());
            assertEquals(256, image.getHeight());
        }
    }

    /** 注册、登录和找回密码均保留原用途、六位验证码与五分钟有效期。 */
    @ParameterizedTest
    @CsvSource({"register,注册验证码", "login,登录验证码", "reset_password,找回密码验证码"})
    void preservesCodeAndPurposeInHtmlAndPlainText(String purpose, String title) throws Exception {
        manager.send("recipient@example.test", purpose, "127.0.0.1");
        assertEquals("【绘灵 AI】" + title, message.getSubject());
        String html = MailTestMessages.html(message);
        var code = Pattern.compile(">([0-9]{6})</p>").matcher(html);
        assertTrue(code.find());
        Part plain = MailTestMessages.find(message, "text/plain");
        assertNotNull(plain, "不支持 HTML 的邮箱也应能读取验证码");
        String text = (String) plain.getContent();
        assertTrue(text.contains(title));
        assertTrue(text.contains(code.group(1)));
        assertTrue(text.contains("5 分钟"));
        assertTrue(html.contains(title));
        assertTrue(html.contains("5 分钟"));
    }

    /** 邮件传输失败仍回滚本次验证码及发送冷却，避免用户被错误限流。 */
    @Test
    void rollsBackCodeWhenMailTransportFails() {
        doThrow(new MailSendException("test transport unavailable")).when(mail).send(message);
        BusinessException error = assertThrows(BusinessException.class,
                () -> manager.send("recipient@example.test", "register", "127.0.0.1"));
        assertEquals("邮件发送失败，请稍后重试", error.getMessage());
        verify(redis).execute(any(RedisScript.class), eq(List.of(
                "huiling:code:cooldown:register:recipient@example.test",
                "huiling:code:register:recipient@example.test")), anyString(), anyString());
    }
}

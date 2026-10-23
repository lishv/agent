package com.xiaoli.aiagent.account;

import com.xiaoli.aiagent.exception.BusinessException;
import com.xiaoli.aiagent.manager.EmailCodeManager;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.serializer.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只写入随机测试邮箱和独立 session 命名空间，结束清理自身键；邮件发送替换为 mock。 */
@EnabledIfEnvironmentVariable(named="RUN_REDIS_INTEGRATION_TESTS", matches="true")
class RedisSessionAndCodeTest {
    private String password() throws Exception {
        String local=Files.readString(Path.of("src/main/resources/application-local.yml"));
        var pattern=Pattern.compile("(?m)^      password: \\$\\{REDIS_PASSWORD:([^}]+)}").matcher(local);
        assertTrue(pattern.find()); return System.getenv().getOrDefault("REDIS_PASSWORD",pattern.group(1));
    }
    @Test void redisSessionsPersistAndCodesAreAtomicLimitedAndSingleUse() throws Exception {
        String marker=UUID.randomUUID().toString(); String email="test-"+marker+"@example.test"; String ip="integration-"+marker;
        var configuration=new RedisStandaloneConfiguration("127.0.0.1",6379); configuration.setDatabase(2); configuration.setPassword(password());
        var factory=new LettuceConnectionFactory(configuration); factory.afterPropertiesSet(); factory.start();
        var strings=new StringRedisTemplate(factory);
        String namespace="huiling:test:session:"+marker;
        try {
            var mail=mock(JavaMailSender.class);
            var mime=new MimeMessage(Session.getInstance(new Properties())); when(mail.createMimeMessage()).thenReturn(mime);
            var codes=new EmailCodeManager(strings,mail); ReflectionTestUtils.setField(codes,"fromEmail","sender@example.test");
            codes.send(email,"register",ip);
            String html=MailTestMessages.html(mime); var match=Pattern.compile(">([0-9]{6})</p>").matcher(html); assertTrue(match.find()); String code=match.group(1);
            assertThrows(BusinessException.class,()->codes.send(email,"register",ip));
            var outcomes=new CopyOnWriteArrayList<Boolean>();
            try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures=new ArrayList<>();
                for(int i=0;i<2;i++) futures.add(executor.submit(()->{ try { codes.consume(email,"register",code); outcomes.add(true); } catch(BusinessException e) { outcomes.add(false); } }));
                for(var future:futures) future.get(5,TimeUnit.SECONDS);
            }
            assertEquals(1,outcomes.stream().filter(Boolean::booleanValue).count());
            assertThrows(BusinessException.class,()->codes.consume(email,"register",code));
            codes.send(email,"reset_password",ip);
            html=MailTestMessages.html(mime); match=Pattern.compile(">([0-9]{6})</p>").matcher(html); assertTrue(match.find()); String resetCode=match.group(1);
            String wrong=String.format("%06d",(Integer.parseInt(resetCode)+1)%1_000_000);
            for(int i=0;i<5;i++) assertThrows(BusinessException.class,()->codes.consume(email,"reset_password",wrong));
            assertThrows(BusinessException.class,()->codes.consume(email,"reset_password",resetCode));
            codes.send(email,"login",ip);
            assertEquals("【绘灵 AI】登录验证码",mime.getSubject());
            html=MailTestMessages.html(mime); match=Pattern.compile(">([0-9]{6})</p>").matcher(html); assertTrue(match.find()); String loginCode=match.group(1);
            assertThrows(BusinessException.class,()->codes.consume(email,"register",loginCode));
            assertThrows(BusinessException.class,()->codes.consume(email,"reset_password",loginCode));
            codes.consume(email,"login",loginCode);
            assertThrows(BusinessException.class,()->codes.consume(email,"login",loginCode));
            assertThrows(BusinessException.class,()->codes.send(email,"invalid-purpose",ip));
            verify(mail,times(3)).send(any(MimeMessage.class));

            var template=new RedisTemplate<String,Object>(); template.setConnectionFactory(factory);
            template.setKeySerializer(new StringRedisSerializer()); template.setHashKeySerializer(new StringRedisSerializer());
            template.setValueSerializer(new JdkSerializationRedisSerializer()); template.setHashValueSerializer(new JdkSerializationRedisSerializer()); template.afterPropertiesSet();
            var repository=new RedisSessionRepository(template); repository.setRedisKeyNamespace(namespace); repository.setDefaultMaxInactiveInterval(Duration.ofDays(30));
            org.springframework.session.SessionRepository<org.springframework.session.Session> sessionStore=(org.springframework.session.SessionRepository)repository;
            org.springframework.session.Session session=sessionStore.createSession(); session.setAttribute("userId",101L); session.setAttribute("authVersion",1); sessionStore.save(session);
            var restarted=new RedisSessionRepository(template); restarted.setRedisKeyNamespace(namespace);
            org.springframework.session.Session restored=restarted.findById(session.getId()); assertNotNull(restored); assertEquals(101L,(Long)restored.getAttribute("userId"));
            assertEquals(Duration.ofDays(30),restored.getMaxInactiveInterval());
            var keys=strings.keys(namespace+"*"); assertNotNull(keys); assertFalse(keys.isEmpty());
            assertTrue(keys.stream().anyMatch(key->strings.getExpire(key)>Duration.ofDays(29).getSeconds()));
            restarted.deleteById(session.getId()); assertNull(repository.findById(session.getId()));
        } finally {
            var codeKeys=strings.keys("huiling:*"+email+"*"); if(codeKeys!=null&&!codeKeys.isEmpty()) strings.delete(codeKeys);
            strings.delete(List.of("huiling:limit:mail-ip:"+ip));
            var sessionKeys=strings.keys(namespace+"*"); if(sessionKeys!=null&&!sessionKeys.isEmpty()) strings.delete(sessionKeys);
            factory.destroy();
        }
    }
}

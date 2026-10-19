package com.xiaoli.aiagent.account;

import com.xiaoli.aiagent.service.impl.*;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoli.aiagent.config.*;
import com.xiaoli.aiagent.controller.*;
import com.xiaoli.aiagent.domain.entity.AppUser;
import com.xiaoli.aiagent.exception.*;
import com.xiaoli.aiagent.manager.EmailCodeManager;
import com.xiaoli.aiagent.mapper.*;
import com.xiaoli.aiagent.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountConversationTest {
    @TempDir Path directory;
    @Configuration(proxyBeanMethods=false)
    @Import({DatabaseConfig.class, AccountServiceImpl.class, ConversationServiceImpl.class, AccountWebConfig.class, ConversationContextServiceImpl.class})
    static class Beans {
        @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean EmailCodeManager codes() { return mock(EmailCodeManager.class); }
        @Bean ContextMemoryService contexts() { return mock(ContextMemoryService.class); }
    }
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class))
                .withUserConfiguration(Beans.class).withPropertyValues("spring.datasource.url=jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "chat-memory.base-dir="+directory)
                .withInitializer(ctx -> { });
    }
    private void schema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,email VARCHAR(254) UNIQUE NOT NULL,password_hash VARCHAR(100),display_name VARCHAR(40),auth_version INT,created_at TIMESTAMP,updated_at TIMESTAMP,is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE chat_session(id VARCHAR(64) PRIMARY KEY,user_id BIGINT,app_id VARCHAR(10),title VARCHAR(100),memory_file_path VARCHAR(1024),transcript_file_path VARCHAR(1024),created_at TIMESTAMP,updated_at TIMESTAMP,is_deleted INT DEFAULT 0)");
    }
    private AppUser register(AccountService service, String email) {
        var request = new MockHttpServletRequest(); service.csrf(request);
        return service.register(email,"123456","password123","password123","昵称",request);
    }
    @Test void registrationHashesPasswordsAndPasswordChangesRevokeOldSessions() {
        context().run(ctx -> {
            assertNull(ctx.getStartupFailure()); schema(ctx.getBean(JdbcTemplate.class));
            var service=ctx.getBean(AccountService.class); var users=ctx.getBean(UserMapper.class);
            var request=new MockHttpServletRequest(); String token=service.csrf(request);
            var user=service.register(" Alice@EXAMPLE.test ","123456","password123","password123","Alice",request);
            assertEquals("alice@example.test",user.getEmail());
            assertNotEquals("password123",users.selectById(user.getId()).getPasswordHash());
            assertTrue(new BCryptPasswordEncoder().matches("password123",user.getPasswordHash()));
            assertNotEquals(token,service.csrf(request));
            assertThrows(BusinessException.class,()->service.register("alice@example.test","123456","password123","password123","Alice",new MockHttpServletRequest()));
            var older=new MockHttpServletRequest(); older.getSession().setAttribute("userId",user.getId()); older.getSession().setAttribute("authVersion",1);
            service.change(user,"password123","newpassword123","newpassword123");
            assertNull(service.current(older));
            assertThrows(BusinessException.class,()->service.login("alice@example.test","password123",new MockHttpServletRequest()));
            assertEquals(user.getId(),service.login("alice@example.test","newpassword123",new MockHttpServletRequest()).getId());
            assertFalse(service.view(user).toString().contains("password123"));
        });
    }
    @Test void ownersAppsDiskRestoreAndLogicalDeletionAreEnforced() {
        context().run(ctx -> {
            assertNull(ctx.getStartupFailure()); var jdbc=ctx.getBean(JdbcTemplate.class); schema(jdbc);
            var accounts=ctx.getBean(AccountService.class); var chats=ctx.getBean(ConversationService.class);
            var alice=register(accounts,"alice@example.test"); var bob=register(accounts,"bob@example.test");
            var love=chats.begin(alice.getId(),"love","room-love","你好我是小李"); love.loveChunk("恋爱历史"); love.complete(); love.finish();
            var manus=chats.begin(alice.getId(),"manus","room-manus","你好我是小李");
            manus.question(Map.of("questionId","question-1","runId","run-1","question","具体要求？"));
            manus.answer("question-1","真实补充"); manus.step(1,"已检索资料"); manus.finalAnswer("执行完成"); manus.complete(); manus.finish();
            assertEquals(2,chats.list(alice.getId(),null).size()); assertEquals(1,chats.list(alice.getId(),"love").size());
            assertTrue(chats.list(bob.getId(),null).isEmpty());
            assertThrows(BusinessException.class,()->chats.detail(bob.getId(),"room-love"));
            assertThrows(BusinessException.class,()->chats.delete(bob.getId(),"room-love"));
            assertThrows(BusinessException.class,()->chats.begin(alice.getId(),"manus","room-love","混用应用"));
            var restored=new ConversationServiceImpl(ctx.getBean(ChatSessionMapper.class),ctx.getBean(ObjectMapper.class),ctx.getBean(ContextMemoryService.class),directory.toString()).detail(alice.getId(),"room-manus");
            assertEquals(List.of("user","assistant","user","assistant","assistant"),restored.messages().stream().map(m->m.path("role").asText()).toList());
            assertEquals("真实补充",restored.messages().get(2).path("content").asText());
            assertEquals("progress",restored.messages().get(3).path("kind").asText());
            assertEquals("final",restored.messages().get(4).path("kind").asText());
            var row=ctx.getBean(ChatSessionMapper.class).findIncludingDeleted("room-love");
            assertEquals("love/"+alice.getId()+"_room-love.kryo",row.getMemoryFilePath());
            Path transcript=directory.resolve(row.getTranscriptFilePath()); assertTrue(Files.exists(transcript));
            chats.delete(alice.getId(),"room-love");
            assertEquals(1,jdbc.queryForObject("SELECT is_deleted FROM chat_session WHERE id='room-love'",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM chat_session",Integer.class));
            assertFalse(Files.exists(transcript)); assertEquals(1,chats.list(alice.getId(),null).size());
            assertThrows(BusinessException.class,()->chats.begin(alice.getId(),"love","room-love","不允许复活"));
            chats.delete(alice.getId(),"room-love");
        });
    }
    @Test void renamingEnforcesOwnershipAndCsrfAndPreservesTheRunningTask() {
        context().run(ctx -> {
            schema(ctx.getBean(JdbcTemplate.class));
            var accounts = ctx.getBean(AccountService.class);
            var chats = ctx.getBean(ConversationService.class);
            var request = new MockHttpServletRequest();
            var alice = accounts.register("rename@example.test", "123456", "password123", "password123", "Alice", request);
            var bob = register(accounts, "other@example.test");
            var journal = chats.begin(alice.getId(), "manus", "rename-room", "原始问题");
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            journal.onCancel(() -> cancelled.set(true));
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConversationController(chats, accounts, ctx.getBean(ConversationContextService.class)))
                    .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(ctx.getBean(HandlerInterceptor.class)).build();
            var session = (MockHttpSession) request.getSession();
            mvc.perform(put("/chats/rename-room/title").session(session).contentType("application/json").content("{\"title\":\"新的任务\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/chats/rename-room/title").session(session).header("X-CSRF-TOKEN", accounts.csrf(request))
                            .contentType("application/json").content("{\"title\":\"  新的任务  \"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("新的任务")).andExpect(jsonPath("$.running").value(true));
            assertFalse(cancelled.get());
            assertThrows(BusinessException.class, () -> chats.rename(bob.getId(), "rename-room", "无权修改"));
            assertThrows(BusinessException.class, () -> chats.rename(alice.getId(), "rename-room", "  "));
            assertThrows(BusinessException.class, () -> chats.rename(alice.getId(), "rename-room", "字".repeat(101)));
            journal.finalAnswer("正常完成原来的任务"); journal.complete(); journal.finish();
            assertEquals("新的任务", chats.detail(alice.getId(), "rename-room").title());
            assertEquals("正常完成原来的任务", chats.detail(alice.getId(), "rename-room").messages().getLast().path("content").asText());
            chats.delete(alice.getId(), "rename-room");
            assertThrows(BusinessException.class, () -> chats.rename(alice.getId(), "rename-room", "不能复活"));
        });
    }

    @Test void deletingAnActiveConversationClosesLateWritersAndRetriesFileCleanup() {
        context().run(ctx -> {
            var jdbc = ctx.getBean(JdbcTemplate.class); schema(jdbc);
            var alice = register(ctx.getBean(AccountService.class), "delete@example.test");
            var chats = ctx.getBean(ConversationService.class);
            var memory = ctx.getBean(ContextMemoryService.class);
            var journal = chats.begin(alice.getId(), "love", "delete-running", "继续回答");
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            journal.onCancel(() -> cancelled.set(true)); journal.loveChunk("部分回答");
            Path transcript = directory.resolve("love/" + journal.memoryId() + ".json");
            doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "清理失败")).doNothing()
                    .when(memory).delete("love", journal.memoryId());
            assertThrows(BusinessException.class, () -> chats.delete(alice.getId(), "delete-running"));
            assertTrue(cancelled.get()); assertFalse(journal.running());
            assertEquals(1, jdbc.queryForObject("SELECT is_deleted FROM chat_session WHERE id='delete-running'", Integer.class));
            assertTrue(chats.list(alice.getId(), null).isEmpty());
            chats.delete(alice.getId(), "delete-running");
            assertFalse(Files.exists(transcript));
            journal.loveChunk("迟到回复"); journal.step(1, "迟到步骤"); journal.finalAnswer("迟到答案"); journal.finish();
            assertFalse(Files.exists(transcript));
            chats.delete(alice.getId(), "delete-running");
            verify(memory, times(3)).delete("love", journal.memoryId());
            assertThrows(BusinessException.class, () -> chats.begin(alice.getId(), "love", "delete-running", "不能复活"));
        });
    }

    @Test void deletionWithRealRepositoriesRemovesMemoryTranscriptAndSummaryBackup() {
        context().run(ctx -> {
            schema(ctx.getBean(JdbcTemplate.class));
            var alice = register(ctx.getBean(AccountService.class), "files@example.test");
            var love = new com.xiaoli.aiagent.chatmemory.FileBasedChatMemoryRepository(directory.resolve("love").toString());
            var manus = new com.xiaoli.aiagent.chatmemory.FileBasedChatMemoryRepository(directory.resolve("manus").toString());
            var config = new ContextMemoryProperties(); config.setAutoEnabled(false);
            var tokens = new com.xiaoli.aiagent.chatmemory.ContextTokenEstimator();
            var model = mock(org.springframework.ai.chat.model.ChatModel.class);
            when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("保留目标和待办。")))));
            var memory = new ContextMemoryServiceImpl(love, manus, model, model, config, tokens,
                    new com.xiaoli.aiagent.chatmemory.HookContextSummaryGenerator(tokens), directory.toString());
            try {
                var chats = new ConversationServiceImpl(ctx.getBean(ChatSessionMapper.class), ctx.getBean(ObjectMapper.class), memory, directory.toString());
                var first = chats.begin(alice.getId(), "love", "purge-love", "我的目标"); first.loveChunk("原回复"); first.complete(); first.finish();
                var second = chats.begin(alice.getId(), "manus", "keep-manus", "保留任务"); second.finalAnswer("其他会话的回复"); second.complete(); second.finish();
                List<org.springframework.ai.chat.messages.Message> history = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    history.add(new org.springframework.ai.chat.messages.UserMessage("第" + i + "轮目标".repeat(100)));
                    history.add(new org.springframework.ai.chat.messages.AssistantMessage("第" + i + "轮回答".repeat(100)));
                }
                memory.save("love", first.memoryId(), history); memory.save("manus", second.memoryId(), history);
                memory.request("love", first.memoryId());
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (Set.of("queued", "compressing").contains(memory.usage("love", first.memoryId()).status())) {
                    if (System.nanoTime() > deadline) fail("压缩没有完成"); Thread.sleep(10);
                }
                assertEquals("completed", memory.usage("love", first.memoryId()).status());
                chats.delete(alice.getId(), first.chatId());
                assertFalse(Files.exists(directory.resolve("love/" + first.memoryId() + ".kryo")));
                assertFalse(Files.exists(directory.resolve("love/" + first.memoryId() + ".json")));
                try (var files = Files.list(directory.resolve("love/context-backups"))) { assertEquals(0, files.count()); }
                assertTrue(Files.exists(directory.resolve("manus/" + second.memoryId() + ".kryo")));
                assertTrue(Files.exists(directory.resolve("manus/" + second.memoryId() + ".json")));
                assertEquals("其他会话的回复", chats.detail(alice.getId(), second.chatId()).messages().getLast().path("content").asText());
                assertEquals(1, ctx.getBean(ChatSessionMapper.class).findIncludingDeleted(first.chatId()).getIsDeleted());
            } finally { memory.shutdown(); }
        });
    }

    @Test void runningStatusAndCancellationAreScopedToTheConversationOwner() {
        context().run(ctx -> {
            schema(ctx.getBean(JdbcTemplate.class));
            var accounts = ctx.getBean(AccountService.class); var chats = ctx.getBean(ConversationService.class);
            var alice = register(accounts, "background-alice@example.test"); var bob = register(accounts, "background-bob@example.test");
            var journal = chats.begin(alice.getId(), "love", "running-room", "继续回答");
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            journal.onCancel(() -> cancelled.set(true)); journal.loveChunk("正在回答");
            assertTrue(chats.detail(alice.getId(), "running-room").running());
            assertTrue(chats.list(alice.getId(), null).getFirst().running());
            assertThrows(BusinessException.class, () -> chats.cancel(bob.getId(), "running-room"));
            assertFalse(cancelled.get());
            assertTrue(chats.cancel(alice.getId(), "running-room"));
            assertTrue(cancelled.get());
            assertFalse(chats.detail(alice.getId(), "running-room").running());
            assertEquals("stopped", chats.detail(alice.getId(), "running-room").messages().getLast().path("status").asText());
            assertFalse(chats.cancel(alice.getId(), "running-room"));
        });
    }
    @Test void contextEndpointsEnforceOwnershipDeletionAndCsrfWithoutExposingMemoryContents() {
        context().run(ctx -> {
            schema(ctx.getBean(JdbcTemplate.class));
            var accounts = ctx.getBean(AccountService.class); var chats = ctx.getBean(ConversationService.class);
            var aliceRequest = new MockHttpServletRequest();
            var alice = accounts.register("context-alice@example.test", "123456", "password123", "password123", "Alice", aliceRequest);
            var bobRequest = new MockHttpServletRequest();
            accounts.register("context-bob@example.test", "123456", "password123", "password123", "Bob", bobRequest);
            var journal = chats.begin(alice.getId(), "love", "context-owner", "重要关系与目标"); journal.complete(); journal.finish();
            var engine = ctx.getBean(ContextMemoryService.class);
            var usage = new com.xiaoli.aiagent.domain.vo.ContextUsageView(true,true,1200,32768,4096,28672,4.2,true,70,4,"idle",0,0,0,0,"");
            when(engine.usage("love", alice.getId()+"_context-owner")).thenReturn(usage);
            when(engine.request("love", alice.getId()+"_context-owner")).thenReturn(usage);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConversationController(chats,accounts,ctx.getBean(ConversationContextService.class)))
                    .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(ctx.getBean(HandlerInterceptor.class)).build();
            var aliceSession = (MockHttpSession) aliceRequest.getSession(); var bobSession = (MockHttpSession) bobRequest.getSession();
            mvc.perform(get("/chats/context-owner/context").session(aliceSession)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.estimatedTokens").value(1200)).andExpect(jsonPath("$.summary").doesNotExist()).andExpect(jsonPath("$.memoryFilePath").doesNotExist());
            mvc.perform(get("/chats/context-owner/context").session(bobSession)).andExpect(status().isNotFound());
            mvc.perform(post("/chats/context-owner/context/compact").session(aliceSession)).andExpect(status().isForbidden());
            mvc.perform(post("/chats/context-owner/context/compact").session(aliceSession).header("X-CSRF-TOKEN",accounts.csrf(aliceRequest)))
                    .andExpect(status().isOk());
            verify(engine).request("love", alice.getId()+"_context-owner");
            chats.delete(alice.getId(),"context-owner");
            mvc.perform(get("/chats/context-owner/context").session(aliceSession)).andExpect(status().isNotFound());
        });
    }
    @Test void codeLoginAndNicknameChangesPersistWithoutChangingPasswordOrRevokingSessions() {
        context().run(ctx -> {
            assertNull(ctx.getStartupFailure()); schema(ctx.getBean(JdbcTemplate.class));
            var service=ctx.getBean(AccountService.class); var users=ctx.getBean(UserMapper.class); var codes=ctx.getBean(EmailCodeManager.class);
            var original=new MockHttpServletRequest();
            var alice=service.register("alice@example.test","123456","password123","password123","Alice",original);
            var bob=register(service,"bob@example.test");
            var request=new MockHttpServletRequest(); String token=service.csrf(request); String sessionId=request.getSession().getId();
            var signedIn=service.codeLogin(" Alice@EXAMPLE.test ","345678",request);
            verify(codes).consume("alice@example.test","login","345678");
            assertEquals(alice.getId(),signedIn.getId()); assertEquals(alice.getId(),service.current(request).getId());
            assertNotEquals(sessionId,request.getSession().getId()); assertNotEquals(token,service.csrf(request));
            String hash=signedIn.getPasswordHash(); int version=signedIn.getAuthVersion();
            var renamed=service.updateNickname(signedIn,"  绘灵小李  ");
            assertEquals("绘灵小李",renamed.getDisplayName()); assertEquals("绘灵小李",users.selectById(alice.getId()).getDisplayName());
            assertEquals("绘灵小李",service.current(original).getDisplayName());
            assertEquals(hash,users.selectById(alice.getId()).getPasswordHash()); assertEquals(version,users.selectById(alice.getId()).getAuthVersion());
            assertEquals("昵称",users.selectById(bob.getId()).getDisplayName());
            assertThrows(BusinessException.class,()->service.updateNickname(signedIn,"   "));
            assertThrows(BusinessException.class,()->service.updateNickname(signedIn,"字".repeat(41)));
            doThrow(new BusinessException(ErrorCode.PARAMS_ERROR,"验证码错误或已过期")).when(codes).consume("alice@example.test","login","000000");
            var invalid=new MockHttpServletRequest();
            assertThrows(BusinessException.class,()->service.codeLogin("alice@example.test","000000",invalid));
            assertNull(service.current(invalid));
            assertThrows(BusinessException.class,()->service.codeLogin("missing@example.test","345678",new MockHttpServletRequest()));
            assertThrows(BusinessException.class,()->service.register("blank@example.test","123456","password123","password123"," ",new MockHttpServletRequest()));
            verify(codes,never()).consume(eq("blank@example.test"),anyString(),anyString());
            service.reset("alice@example.test","234567","resetpassword123","resetpassword123");
            assertNull(service.current(request)); assertNull(service.current(original));
            var fresh=service.codeLogin("alice@example.test","654321",new MockHttpServletRequest());
            assertEquals(version+1,fresh.getAuthVersion()); assertEquals("绘灵小李",fresh.getDisplayName());
        });
    }
    @Test void httpEndpointsRejectAnonymousAndCsrfRequests() {
        context().run(ctx -> {
            assertNull(ctx.getStartupFailure()); schema(ctx.getBean(JdbcTemplate.class));
            var service=ctx.getBean(AccountService.class); var codes=ctx.getBean(EmailCodeManager.class);
            MockMvc mvc=MockMvcBuilders.standaloneSetup(new AccountController(service),new ConversationController(ctx.getBean(ConversationService.class),service,ctx.getBean(ConversationContextService.class)))
                    .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(ctx.getBean(HandlerInterceptor.class)).build();
            mvc.perform(get("/chats")).andExpect(status().isUnauthorized());
            var start=mvc.perform(get("/auth/session")).andExpect(status().isOk()).andReturn();
            var session=(MockHttpSession)start.getRequest().getSession(); var json=ctx.getBean(ObjectMapper.class);
            String token=json.readTree(start.getResponse().getContentAsString()).path("csrfToken").asText();
            mvc.perform(post("/auth/nickname").session(session).header("X-CSRF-TOKEN",token).contentType("application/json").content("{\"displayName\":\"昵称\"}")).andExpect(status().isUnauthorized());
            String body="{\"email\":\"web@example.test\",\"code\":\"123456\",\"password\":\"password123\",\"confirmPassword\":\"password123\",\"displayName\":\"Web\"}";
            mvc.perform(post("/auth/register").session(session).contentType("application/json").content(body)).andExpect(status().isForbidden());
            mvc.perform(post("/auth/register").session(session).header("X-CSRF-TOKEN",token).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.user.id").isString());
            mvc.perform(get("/chats").session(session)).andExpect(status().isOk());
            mvc.perform(get("/chats").session(session).header("X-Account-ID","another-user")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(ErrorCode.ACCOUNT_CHANGED.getCode()));
            String currentToken=(String)session.getAttribute("csrfToken");
            mvc.perform(post("/auth/nickname").session(session).header("X-CSRF-TOKEN",currentToken).header("X-Account-ID","another-user").contentType("application/json").content("{\"displayName\":\"昵称\"}")).andExpect(status().isConflict());
            mvc.perform(post("/auth/nickname").session(session).header("X-CSRF-TOKEN",currentToken).contentType("application/json").content("{\"displayName\":\" \"}")).andExpect(status().isBadRequest());
            mvc.perform(post("/auth/nickname").session(session).header("X-CSRF-TOKEN",currentToken).contentType("application/json").content("{\"displayName\":\"绘灵小李\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("绘灵小李"));
            mvc.perform(post("/auth/code-login").session(session).header("X-CSRF-TOKEN",currentToken).contentType("application/json").content("{\"email\":\"web@example.test\",\"code\":\"345678\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.user.displayName").value("绘灵小李"));
            verify(codes).consume("web@example.test","login","345678");
            mvc.perform(post("/auth/logout").session(session).header("X-CSRF-TOKEN",token)).andExpect(status().isForbidden());
            mvc.perform(post("/auth/logout").session(session).header("X-CSRF-TOKEN",session.getAttribute("csrfToken"))).andExpect(status().isOk());
            mvc.perform(get("/chats")).andExpect(status().isUnauthorized());
        });
    }

    /** 恋爱聊天入口必须通过 Session、CSRF 和账号切换检查，并把真实登录身份交给业务层。 */
    @Test void loveChatAuthenticatesTheOwnerAndRejectsMissingCsrfOrChangedAccounts() {
        context().run(ctx -> {
            schema(ctx.getBean(JdbcTemplate.class));
            var accounts = ctx.getBean(AccountService.class);
            var application = mock(ChatApplicationService.class);
            when(application.loveStream(anyLong(), eq("你好"), eq("auth-love")))
                    .thenReturn(reactor.core.publisher.Flux.just("认证后的恋爱回复"));
            var mvc = MockMvcBuilders.standaloneSetup(new AiController(application, accounts))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .addInterceptors(ctx.getBean(HandlerInterceptor.class))
                    .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                            new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(ctx.getBean(ObjectMapper.class)))
                    .build();
            String body = "{\"message\":\"你好\",\"chatId\":\"auth-love\"}";
            var anonymous = new MockHttpServletRequest();
            String anonymousToken = accounts.csrf(anonymous);
            mvc.perform(post("/ai/love_app/chat/sse").session((MockHttpSession) anonymous.getSession())
                            .header("X-CSRF-TOKEN", anonymousToken).contentType("application/json").content(body))
                    .andExpect(status().isUnauthorized());
            var login = new MockHttpServletRequest();
            var user = accounts.register("love-auth@example.test", "123456", "password123", "password123", "Love", login);
            var session = (MockHttpSession) login.getSession();
            String token = accounts.csrf(login);
            mvc.perform(post("/ai/love_app/chat/sse").session(session).contentType("application/json").content(body))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/ai/love_app/chat/sse").session(session).header("X-CSRF-TOKEN", token)
                            .header("X-Account-ID", "another-user").contentType("application/json").content(body))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40901));
            verifyNoInteractions(application);
            var request = mvc.perform(post("/ai/love_app/chat/sse").session(session).header("X-CSRF-TOKEN", token)
                            .header("X-Account-ID", user.getId()).contentType("application/json").content(body))
                    .andExpect(request().asyncStarted()).andReturn();
            request.getAsyncResult(5000);
            mvc.perform(asyncDispatch(request)).andExpect(status().isOk());
            assertTrue(request.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("认证后的恋爱回复"));
            verify(application).loveStream(user.getId(), "你好", "auth-love");
        });
    }
}

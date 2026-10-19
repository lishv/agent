package com.xiaoli.aiagent.controller;

import com.xiaoli.aiagent.domain.dto.RenameConversationRequest;
import com.xiaoli.aiagent.domain.vo.ContextUsageView;
import com.xiaoli.aiagent.domain.vo.ConversationViews.*;
import com.xiaoli.aiagent.service.AccountService;
import com.xiaoli.aiagent.service.ConversationContextService;
import com.xiaoli.aiagent.service.ConversationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 当前用户的会话记录、任务取消和上下文管理接口。 */
@RestController
@RequestMapping("/chats")
@RequiredArgsConstructor
public class ConversationController {
    private final ConversationService conversations;
    private final AccountService accounts;
    private final ConversationContextService contexts;

    /** 返回当前用户最近的会话，可按应用筛选，最多返回 100 条。 */
    @GetMapping
    public List<Summary> list(
            @RequestParam(required = false) String appId, HttpServletRequest request) {
        return conversations.list(accounts.requireUser(request).getId(), appId);
    }

    /** 校验归属后返回完整页面消息及当前运行状态。 */
    @GetMapping("/{id}")
    public Detail detail(@PathVariable String id, HttpServletRequest request) {
        return conversations.detail(accounts.requireUser(request).getId(), id);
    }

    /** 逻辑删除会话并清理任务和文件；清理失败后可再次请求重试。 */
    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@PathVariable String id, HttpServletRequest request) {
        conversations.delete(accounts.requireUser(request).getId(), id);
        return Map.of("deleted", true);
    }

    /** 只修改当前账号拥有的会话标题，执行中的回答不受影响。 */
    @PutMapping("/{id}/title")
    public Summary rename(
            @PathVariable String id,
            @Valid @RequestBody RenameConversationRequest body,
            HttpServletRequest request) {
        return conversations.rename(accounts.requireUser(request).getId(), id, body.title());
    }

    /** 取消本会话正在执行的任务；无运行中任务时返回 cancelled=false。 */
    @PostMapping("/{id}/cancel")
    public Map<String, Boolean> cancel(@PathVariable String id, HttpServletRequest request) {
        return Map.of("cancelled", conversations.cancel(accounts.requireUser(request).getId(), id));
    }

    /** 查询上下文的估算占用及压缩状态，不返回模型记忆内容。 */
    @GetMapping("/{id}/context")
    public ContextUsageView context(@PathVariable String id, HttpServletRequest request) {
        return contexts.usage(accounts.requireUser(request).getId(), id);
    }

    /** 请求压缩本会话上下文；执行中的任务在下一个安全位置处理。 */
    @PostMapping("/{id}/context/compact")
    public ContextUsageView compact(@PathVariable String id, HttpServletRequest request) {
        return contexts.compact(accounts.requireUser(request).getId(), id);
    }
}

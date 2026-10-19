package com.xiaoli.aiagent.controller;

import com.xiaoli.aiagent.domain.dto.ChatRequests.ChatRequest;
import com.xiaoli.aiagent.domain.dto.ChatRequests.HumanAnswerRequest;
import com.xiaoli.aiagent.exception.ErrorResponse;
import com.xiaoli.aiagent.service.AccountService;
import com.xiaoli.aiagent.service.ChatApplicationService;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Flux;

import java.util.Map;

/** 前端聊天入口：POST 流式请求及智能体追问回答。取消任务统一使用会话接口。 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {
    private static final String SSE = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8";
    private final ChatApplicationService chats;
    private final AccountService accounts;

    /**
     * 启动恋爱助手对话，将文本片段包装为 SSE；流内异常转换为 error 事件。
     *
     * @param body 消息和会话标识
     * @param request HTTP 请求对象
     * @return 文本片段及可能的错误事件流
     */
    @PostMapping(value = "/love_app/chat/sse", produces = SSE)
    public Flux<ServerSentEvent<String>> loveChat(
            @RequestBody ChatRequest body, HttpServletRequest request) {
        return chats.loveStream(
                        accounts.requireUser(request).getId(), body.message(), body.chatId())
                .map(chunk -> ServerSentEvent.builder(chunk).build())
                .onErrorResume(
                        error ->
                                Flux.just(
                                        ServerSentEvent.builder(ErrorResponse.from(error).message())
                                                .event("error")
                                                .build()));
    }

    /**
     * 启动当前用户的智能体任务，返回步骤、追问和最终答案的 SSE 通道。
     *
     * @param body 智能体对话请求体
     * @param request HTTP 请求对象
     * @return SSE 发送器
     */
    @PostMapping(value = "/manus/chat", produces = SSE)
    public SseEmitter manusChat(@RequestBody ChatRequest body, HttpServletRequest request) {
        return chats.manusStream(accounts.requireUser(request).getId(), body.message(), body.chatId(), body.runId());}

    /**
     * 校验任务归属并提交追问答案，让等待中的智能体继续执行。
     *
     * @param body 智能体追问回答请求体
     * @param request HTTP 请求对象
     * @return accepted=true 表示答案已被当前任务接收
     */
    @PostMapping("/manus/answer")
    public Map<String, Boolean> answer(
            @RequestBody HumanAnswerRequest body, HttpServletRequest request) {
        chats.answer(
                accounts.requireUser(request).getId(),
                body.runId(),
                body.questionId(),
                body.answer());
        return Map.of("accepted", true);
    }
}

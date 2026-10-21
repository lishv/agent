package com.xiaoli.aiagent.advisor;

import com.xiaoli.aiagent.service.ContextMemoryService;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** 在模型输入与完整响应之间维护恋爱记忆，替代按 20 条消息淘汰的窗口。 */
public class CompactingMemoryAdvisor implements BaseAdvisor {
    private final ContextMemoryService contextMemoryService;
    public CompactingMemoryAdvisor(ContextMemoryService memory) { this.contextMemoryService = memory; }

    /** 优先级最低，确保在其他 advisor 之后执行。 */
    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 1000; }
    //找到这次调用对应的记忆标识chatId
    private String getChatId(ChatClientRequest request) { return (String) request.context().get(ChatMemory.CONVERSATION_ID); }

    //准备这次模型调用的完整上下文。
    @Override public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String chatId = getChatId(request);
        // 从请求中提取系统消息,getInstructions() 返回prompt封装里的 List<Message>。
        var systems = request.prompt().getInstructions().stream().filter(SystemMessage.class::isInstance).toList();
        //从请求中接下来提取本次可用工具：
        ToolCallback[] tools = request.prompt().getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null
                ? options.getToolCallbacks().toArray(ToolCallback[]::new) : new ToolCallback[0];

        //然后登记本次固定开销：
        contextMemoryService.registerProfile("love", chatId, String.join("\n", systems.stream().map(Message::getText).toList()), tools, "");

        //读取旧记忆，并登记本轮占用
        var history = contextMemoryService.open("love", chatId);
        try {
            //然后把本次请求中的非系统消息加入历史记录：
            history.addAll(request.prompt().getInstructions().stream().filter(message -> !(message instanceof SystemMessage)).toList());
            //压缩检查：
            var prepared = contextMemoryService.prepare("love", chatId, history);//原历史/ 历史摘要＋保留的近期原文
            //然后重新组合系统提示与准备好的历史：
            var prompt = new ArrayList<Message>(systems); prompt.addAll(prepared);
            //最后返回新的请求：基于原来的 Prompt，替换消息列表，保留其余配置。
            return request.mutate().prompt(request.prompt().mutate().messages(prompt).build()).build();
        } catch (RuntimeException error) { contextMemoryService.close("love", chatId); throw error; }
    }

    //保存模型返回的回答。这个方法保存的是模型记忆，不负责写 Journal 页面记录。
    @Override public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (response.chatResponse() != null) {
            contextMemoryService.append("love", (String) response.context().get(ChatMemory.CONVERSATION_ID),
                    response.chatResponse().getResults().stream().<Message>map(result -> result.getOutput()).toList());
        }
        return response;
    }
    //把同步调用的前置、模型调用、后置处理连接起来。
    @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        boolean opened = false;
        try {
            var prepared = before(request, chain); opened = true;
            return after(chain.nextCall(prepared), chain);
        } finally { if (opened) contextMemoryService.close("love", getChatId(request)); }
    }

   /*
   adviseStream 是流式调用时的记忆管理入口：先 before 准备上下文，然后调用 AI 流式输出，
   用 ChatClientMessageAggregator 把所有 chunk 聚合成完整回复后 after 保存记忆，
   最后 doFinally 关闭记忆。它存在的原因是恋爱大师生产环境用流式输出，必须处理"chunk 聚合后再保存"的问题。
    *///两者是互斥的，选了 .stream() 就不会走 adviseCall。adviseCall 只管 .call() 阻塞调用，adviseStream 只管 .stream() 流式调用
    @Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        var opened = new AtomicBoolean();
        return Mono.fromCallable(() -> {
                    var prepared = before(request, chain); opened.set(true);
                    return prepared; })
                .subscribeOn(getScheduler()).flatMapMany(chain::nextStream)
                .transform(stream -> new ChatClientMessageAggregator()
                        .aggregateChatClientResponse(stream, response -> after(response, chain)))
                .doFinally(signal -> { if (opened.getAndSet(false)) contextMemoryService.close("love", getChatId(request)); });
    }

}

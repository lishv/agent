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
    private final ContextMemoryService memory;
    public CompactingMemoryAdvisor(ContextMemoryService memory) { this.memory = memory; }


    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 1000; }
    private String id(ChatClientRequest request) { return (String) request.context().get(ChatMemory.CONVERSATION_ID); }


    @Override public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String id = id(request);
        var systems = request.prompt().getInstructions().stream().filter(SystemMessage.class::isInstance).toList();
        ToolCallback[] tools = request.prompt().getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null
                ? options.getToolCallbacks().toArray(ToolCallback[]::new) : new ToolCallback[0];
        memory.registerProfile("love", id, String.join("\n", systems.stream().map(Message::getText).toList()), tools, "");
        var history = memory.open("love", id);
        try {
            history.addAll(request.prompt().getInstructions().stream().filter(message -> !(message instanceof SystemMessage)).toList());
            var prepared = memory.prepare("love", id, history);
            var prompt = new ArrayList<Message>(systems); prompt.addAll(prepared);
            return request.mutate().prompt(request.prompt().mutate().messages(prompt).build()).build();
        } catch (RuntimeException error) { memory.close("love", id); throw error; }
    }


    @Override public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (response.chatResponse() != null) {
            memory.append("love", (String) response.context().get(ChatMemory.CONVERSATION_ID),
                    response.chatResponse().getResults().stream().<Message>map(result -> result.getOutput()).toList());
        }
        return response;
    }

    @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        boolean opened = false;
        try {
            var prepared = before(request, chain); opened = true;
            return after(chain.nextCall(prepared), chain);
        } finally { if (opened) memory.close("love", id(request)); }
    }


    @Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        var opened = new AtomicBoolean();
        return Mono.fromCallable(() -> { var prepared = before(request, chain); opened.set(true); return prepared; })
                .subscribeOn(getScheduler()).flatMapMany(chain::nextStream)
                .transform(stream -> new ChatClientMessageAggregator().aggregateChatClientResponse(stream, response -> after(response, chain)))
                .doFinally(signal -> { if (opened.getAndSet(false)) memory.close("love", id(request)); });
    }
}

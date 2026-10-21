package com.xiaoli.aiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.xiaoli.aiagent.agent.model.AgentState;
import com.xiaoli.aiagent.exception.ErrorCode;
import com.xiaoli.aiagent.exception.ErrorResponse;
import com.xiaoli.aiagent.exception.ThrowUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/** 管理任务循环、SSE 连接和结束时的资源清理。 */
@Data
@Slf4j
public abstract class BaseAgent {
    //UTF-8 文本媒体类型,给 SSE 文本事件贴上 UTF-8 标签，保证中文不乱码。
    private static final MediaType UTF8_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);
    // 心跳线程，用于定时发送 SSE 心跳包
    private static final ScheduledExecutorService HEARTBEATS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ai-agent-sse-heartbeat");
        thread.setDaemon(true); // 守护线程，随 JVM 退出而退出
        return thread;
    });

    private String name;// agent名称
    private String systemPrompt;// 系统提示
    private String nextStepPrompt;// 下一步提示
    private String runId;// 任务ID
    private volatile AgentState state = AgentState.IDLE;// 代理状态，默认为IDLE
    // 当前步骤索引，从 1 开始
    private int currentStep = 0;
    // 最大步骤数
    private int maxSteps = 20;
    private ChatClient chatClient;
    private List<Message> messageList = new ArrayList<>();// 当前对话的所有会话记录(包括历史记录)
    private String finalAnswer;// 最终答案
    //下面均为回调函数
    // 内存写入器，用于持久化对话记忆
    //memoryWriter 回调 → saveMemory
    private Consumer<List<Message>> memoryWriter = messages -> { };
    // 上下文准备器，用于压缩上下文
    // contextPreparer 回调 → contextMemoryService.prepareContext
    private UnaryOperator<List<Message>> contextPreparer = messages -> new ArrayList<>(messages);

    //这三个用于将智能体的执行结果和状态写入会话记录中
    //stepObserver journal::step 智能体每执行一步，把步骤编号和内容写入会话记录
    private BiConsumer<Integer, String> stepObserver = (number, text) -> { };// 每一步的观察者
    //finalAnswerObserver journal::finalAnswer 智能体得出最终答案时，把答案写入会话记录
    private Consumer<String> finalAnswerObserver = text -> { };// 最终答案观察者
    //errorObserver journal::failed 智能体出错时，把错误状态写入会话记录
    private Consumer<Throwable> errorObserver = error -> { };// 错误观察者





    //streamCancelAction 由 journal.onCancel 触发：设为 CANCELLED、中断虚拟线程、释放资源、关闭 SSE。
    // 显式取消任务；浏览器断开连接只关闭传输通道。
    private Runnable streamCancelAction = () -> state = AgentState.CANCELLED;// 显式取消任务,后面在runstream里会再次被赋值调用
    /*等同于,被Lambda表达式替换
            // 准备一个“任务”，任务内容是：把 state 改成 CANCELLED
        Runnable streamCancelAction = new Runnable() {
            @Override
            public void run() {
                state = AgentState.CANCELLED;
            }
        };
     */

    /**
     * 执行代理任务
     * @param userPrompt 用户提示
     * @return 代理执行结果
     */
    // 当前前端未使用此同步入口。
    public String run(String userPrompt) {
        validateStart(userPrompt);// 验证任务是否可以开始
        state = AgentState.RUNNING;
        messageList.add(new UserMessage(userPrompt));// 添加用户输入消息到会话记录
        List<String> results = new ArrayList<>();//最终用于返回,本次agent的所有步骤的结果
        try {
            saveMemory();//初始把本次用户输入的消息和历史记录,保存到内存中
            for (int i = 0; i < maxSteps && state == AgentState.RUNNING; i++) {
                currentStep = i + 1;
                //log.info("Executing step {}/{}", currentStep, maxSteps);


                String progress = classifyResult(step());// 分类当前步骤的结果,看是不是最终答案,如果返回空说明是最终答案


                if (StrUtil.isNotBlank(progress)) results.add("Step " + currentStep + ": " + progress);
                if (StrUtil.isNotBlank(finalAnswer)) results.add(finalAnswer);// 如果是最终答案,则添加到结果中,因为最终答案是分类结果,不是当前步骤的结果,所以要单独添加
                saveMemory();// 每次循环结束,把当前对话的所有会话记录(包括历史记录)保存到内存中
            }
            //20步,如果还没有最终答案,则强制终止任务避免无限循环
            if (state == AgentState.RUNNING) {
                state = AgentState.FINISHED;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            return String.join("\n", results);// 返回所有步骤的结果,每个步骤的结果用换行符隔开,返回样子为 Step 1: 搜索文件\n Step 2: 读取内容 \n Step 3: 分析数据
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("error executing agent", e);
            return "执行错误：" + ErrorResponse.from(e).message();
        } finally {
            cleanup();
        }
    }
    /**
     * 执行代理任务并返回 SSE 连接
     * @param userPrompt 用户提示
     * @return SSE 连接
     */
    // 当前前端未使用此简化入口。
    public SseEmitter runStream(String userPrompt) {
        return runStream(userPrompt, emitter -> { }, () -> { });
    }

    /** 在开始执行前绑定交互通道；任务执行独立于浏览器连接，结束或显式取消时收尾。 */
    public synchronized SseEmitter runStream(String userPrompt, Consumer<SseEmitter> onStart/*开始时回调执行的方法*/, Runnable onClose/*结束时回调执行的方法*/) {
        validateStart(userPrompt);
        state = AgentState.RUNNING;

        SseEmitter emitter = new SseEmitter(1_800_000L);//30分钟超时

        AtomicBoolean closed = new AtomicBoolean();// 用于标记是否已关闭连接
        AtomicBoolean detached = new AtomicBoolean();// 用于标记是否已断开连接
        AtomicBoolean cleaned = new AtomicBoolean();// 用于标记是否已清理资源
        AtomicReference<Thread> worker = new AtomicReference<>();// 用于存储任务执行线程的虚拟线程
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();// 用于存储心跳任务

        // 断开连接时执行的方法,1.标记为已断开连接,2.     取消心跳任务,停止像前端发送空包
        Runnable detach = () -> {
            detached.set(true);// 标记为已断开连接
            ScheduledFuture<?> timer = heartbeat.get();// 获取心跳任务
            if (timer != null) timer.cancel(false);// 取消心跳任务,停止像前端发送空包
        };


        // 发布事件到前端时执行的方法,1.判断是否已断开连接,如果已断开连接true,则不发布事件event,2.发布事件event到前端,3.如果发布事件event失败,则断开连接时执行的方法
        Consumer<SseEmitter.SseEventBuilder> publish = event -> {
            if (detached.get()) return;// 判断是否已断开连接,如果已断开连接true,则不发布事件event
            try { emitter.send(event); }// 发布事件event到前端
            catch (IOException | IllegalStateException e) { detach.run(); }
        };


        // 释放资源时执行的方法,标记为已关闭连接,清理资源,断开连接时执行的方法,结束时回调执行的方法
        Runnable release = () -> {
            closed.set(true);// 标记为已关闭连接
            //如果已清理资源,则直接返回,1. 如果已清理资源,则直接返回,2.  如果未清理资源,则清理资源
            if (!cleaned.compareAndSet(false, true)) return;
            detach.run();// 断开连接时执行的方法
            try {
                onClose.run();// 结束时回调执行的方法
            } catch (RuntimeException e) {
                log.error("无法完成任务记录收尾", e);
            } finally {
                cleanup();
            }
        };


        // 关闭连接时执行的方法,设置状态为参数reason原因:cancel或error或finished,并中断任务执行线程,释放资源,通知 Spring 连接结束 ──
        Consumer<AgentState> close = reason -> {
            //1. 如果已关闭连接,则直接返回,2. 如果未关闭连接,则关闭连接
            if (!closed.compareAndSet(false, true)) return;
            // 切换状态为参数reason原因:cancel或error或finished

            state = reason;

            // 如果任务执行线程不是当前线程,则获取当前任务执行线程,并中断任务执行线程
            Thread thread = worker.get();
            if (thread != null && thread != Thread.currentThread()) thread.interrupt();// 中断任务执行的虚拟线程,停止任务执行
            release.run();// 释放资源
            emitter.complete();// 通知 Spring SSE连接结束 ─
        };

        //异步流式用,取消任务时执行的方法,覆盖简单的run()需要的方法
        streamCancelAction = () -> close.accept(AgentState.CANCELLED);// 取消任务时执行的方法,为 streamCancelAction方法赋值为AgentState.CANCELLED状态


        emitter.onTimeout(detach);// 超时断开连接时执行的方法,为onTimeout方法赋值为detach方法
        emitter.onError(error -> detach.run());// 错误断开连接时执行的方法,为onError方法赋值为detach方法
        emitter.onCompletion(detach);// 完成断开连接时执行的方法

        try {
            onStart.accept(emitter);// 开始时回调执行的方法,emitter正是从runStream方法中传入的SseEmitter对象到外部
        } catch (RuntimeException e) {
            state = AgentState.ERROR;
            release.run();//释放资源
            emitter.completeWithError(e);//SSE连接结束,并发送错误事件e
            return emitter;
        }
        //心跳必须在worker 之前、onStart 之后设置——因为 onStart 里业务层可能已经开始发 SSE 事
        // 心跳只维护传输通道，断开连接后仍可从会话文件查看进度、回答追问。1.每10秒发送一次空包,2.如果关闭连接或断开连接,则不发送空包
        heartbeat.set(HEARTBEATS.scheduleAtFixedRate(() -> {
            if (closed.get() || detached.get()) return;
            publish.accept(SseEmitter.event().comment("keepalive"));
        }, 10, 10, TimeUnit.SECONDS));


        if (detached.get()) detach.run();//如果这时候已断开连接,则直接返回
        // 启用虚拟线程,执行任务,这是为了不占用公共 ForkJoinPool 的工作线程,保持任务执行的并发性
        worker.set(Thread.ofVirtual().name("ai-agent-" + runId).start(() -> {
            //1.将用户输入userPrompt添加到消息列表messageList中作为用户初始输入
            messageList.add(new UserMessage(userPrompt));
            try {
                saveMemory();// 保存历史提示词与当前用户输入
                for (int i = 0; i < maxSteps && !closed.get() && state == AgentState.RUNNING; i++) {
                    currentStep = i + 1;
                    //log.info("Executing step {}/{}", currentStep, maxSteps);

                    String result = classifyResult(step());//判断当前步骤是中间步骤还是最终步骤

                    if (closed.get()) return;

                    saveMemory();//保存历史提示词与当前用户输入
                    if (closed.get()) return;
                    if (StrUtil.isNotBlank(result)) {//判断当前步骤是中间步骤还是最终步骤,如果是中间步骤,则发布事件到前端

                        // 发布中间步骤事件到前端messagesJson 会话记录
                        stepObserver.accept(currentStep, result);

                        publish.accept(SseEmitter.event().data("Step " + currentStep + ": " + result, UTF8_TEXT));
                    }
                    if (closed.get()) return;
                    if (StrUtil.isNotBlank(finalAnswer)) {//如果是最终步骤,则发布事件到前端
                        finalAnswerObserver.accept(finalAnswer);//写入前端 messagesJson 会话记录
                        publish.accept(SseEmitter.event().name("final_answer").data(finalAnswer, UTF8_TEXT));
                    }
                }
                if (closed.get()) return;
                String reason = state == AgentState.RUNNING ? "max_steps" : "finished";
                if (state == AgentState.RUNNING) {
                    state = AgentState.FINISHED;
                    stepObserver.accept(0, "执行结束：达到最大步骤（" + maxSteps + "）");
                    publish.accept(SseEmitter.event().data("执行结束：达到最大步骤（" + maxSteps + "）", UTF8_TEXT));
                }

                // 发布完成事件到前端
                publish.accept(SseEmitter.event().name("complete").data(Map.of("reason", reason)));

            } catch (Exception e) {
                if (closed.get()) return;
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                try { errorObserver.accept(e); } catch (RuntimeException storageError) { log.error("无法记录任务错误", storageError); }
                publish.accept(SseEmitter.event().name("error").data("执行错误：" + ErrorResponse.from(e).message(), UTF8_TEXT));
            } finally {
                release.run();//释放资源
                emitter.complete();//SSE连接结束
            }
        }));
        return emitter;
    }

    /**
     * 执行任务的步骤,后面需要根据实际情况实现
     * @return
     */
    public abstract String step();


    /**
     * 取消任务,即将任务状态设置为CANCELLED,
     * 并在运行时判断state!=AgentState.RUNNING,则直接返回
     */
    public synchronized void cancelStream() {
        streamCancelAction.run();//即给任务state赋值为CANCELLED,然后在运行时判断state!=AgentState.RUNNING,则直接返回
    }



    /*验证任务是否可以开始,如果任务状态不是IDLE,则抛出异常*/
    private void validateStart(String userPrompt) {
        ThrowUtils.throwIf(state != AgentState.IDLE, ErrorCode.CONFLICT_ERROR, "当前任务已启动或结束，请重新发起任务");
        ThrowUtils.throwIf(StrUtil.isBlank(userPrompt), ErrorCode.PARAMS_ERROR, "任务内容不能为空");
    }



    /*判断当前步骤是中间步骤还是最终步骤*/
    private String classifyResult(String result) {
        if (state == AgentState.FINISHED && StrUtil.isBlank(finalAnswer)) {
            ThrowUtils.throwIf(StrUtil.isBlank(result), ErrorCode.OPERATION_ERROR, "未收到最终回答，请重试");
            finalAnswer = result;
            return null;
        }
        return result;
    }

    private void saveMemory() {
        memoryWriter.accept(new ArrayList<>(messageList));
    }

    protected void cleanup() { }
}

package com.yupi.yuaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.agent.model.AgentState;
import com.yupi.yuaiagent.agent.model.IntentType;
import com.yupi.yuaiagent.agent.sink.BufferedOutputSink;
import com.yupi.yuaiagent.agent.sink.OutputSink;
import com.yupi.yuaiagent.agent.sink.SseOutputSink;
import com.yupi.yuaiagent.rag.QueryRewriter;
import com.yupi.yuaiagent.tools.KnowledgeBaseQueryTool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 * <p>
 * 执行链路：查询重写 → 意图分类 → 按意图路由到对应 handler。
 * <pre>
 *   run / runStream
 *     ├─ execute(prompt, sink)
 *     │    ├─ preProcess  → (intent, processedPrompt)
 *     │    └─ dispatch
 *     │         ├─ REJECT    → handleRejectIntent
 *     │         ├─ CHAT      → handleChatIntent
 *     │         ├─ KNOWLEDGE → handleKnowledgeIntent (失败降级 TASK)
 *     │         └─ TASK      → handleTaskIntent (ReAct 循环)
 *     └─ sink.complete / completeWithError
 * </pre>
 * 子类必须实现 {@link #step()}，定义 ReAct 单步行为。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    // ============== 核心属性 ==============
    private String name;

    // 提示词
    private String systemPrompt;
    private String nextStepPrompt;

    // 代理状态
    private AgentState state = AgentState.IDLE;

    // 执行步骤控制
    private int currentStep = 0;
    private int maxSteps = 10;

    // LLM 大模型
    private ChatClient chatClient;

    // Memory 记忆（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();

    // ============== 协作组件 ==============
    /** 对话记忆组件（多轮上下文检索） */
    private ChatMemory chatMemory;
    /** 会话 ID */
    private String conversationId;

    /** 查询重写器（多轮对话上下文感知） */
    private QueryRewriter queryRewriter;
    /** 意图分类器（驱动路由分发） */
    private IntentClassifier intentClassifier;
    /** 知识库查询工具（KNOWLEDGE 意图直接调用 RAG 链路） */
    private KnowledgeBaseQueryTool knowledgeBaseQueryTool;

    // 拒绝话术常量
    private static final String REJECT_REPLY =
            "非常抱歉，该问题超出了我的服务范围。建议您通过南京大学信息管理学院官方渠道获取更多帮助。";

    // ===================================================================
    //                            对外入口
    // ===================================================================

    /**
     * 同步运行代理，返回最终聚合结果。
     */
    public String run(String userPrompt) {
        BufferedOutputSink sink = new BufferedOutputSink();
        execute(userPrompt, sink);
        return sink.toAggregatedString();
    }

    /**
     * 流式运行代理，过程结果通过 SSE 推送。
     */
    public SseEmitter runStream(String userPrompt) {
        SseEmitter sseEmitter = new SseEmitter(300_000L); // 5 分钟超时
        SseOutputSink sink = new SseOutputSink(sseEmitter);

        // 异步执行，避免阻塞主线程
        CompletableFuture.runAsync(() -> execute(userPrompt, sink));

        // 注册 SSE 生命周期回调
        sseEmitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSE connection timeout");
        });
        sseEmitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSE connection completed");
        });
        return sseEmitter;
    }

    // ===================================================================
    //                       核心执行流程（统一）
    // ===================================================================

    /**
     * 统一执行流程：校验 → 前置处理 → 路由分发。
     * 任何异常都会通过 sink.completeWithError 兜底，保证调用方能拿到响应。
     */
    private void execute(String userPrompt, OutputSink sink) {
        // 1. 基础校验
        if (this.state != AgentState.IDLE) {
            sink.completeWithError("错误：无法从状态运行代理：" + this.state);
            return;
        }
        if (StrUtil.isBlank(userPrompt)) {
            sink.completeWithError("错误：不能使用空提示词运行代理");
            return;
        }

        this.state = AgentState.RUNNING;
        try {
            // 2. 前置处理（查询重写 + 意图分类）
            ProcessedPrompt processed = preProcess(userPrompt);

            // 3. 按意图路由分发
            dispatchByIntent(processed, sink);

            // 4. 标记成功完成
            sink.complete();
        } catch (Exception e) {
            this.state = AgentState.ERROR;
            log.error("[{}] 执行异常", name, e);
            sink.completeWithError("执行错误：" + e.getMessage());
        } finally {
            this.cleanup();
        }
    }

    /**
     * 前置处理：查询重写 + 意图分类，输出干净的 (intent, prompt) 载体。
     * 不再修改任何全局状态，保证执行流程可重入。
     */
    private ProcessedPrompt preProcess(String userPrompt) {
        // Step 1: 查询重写（补全语义、消除指代歧义）
        String processedPrompt = userPrompt;
        if (queryRewriter != null && chatMemory != null && StrUtil.isNotBlank(conversationId)) {
            processedPrompt = queryRewriter.doQueryRewrite(userPrompt, chatMemory, conversationId);
            log.info("[{}] 查询重写：{} -> {}", name, userPrompt, processedPrompt);
        }

        // Step 2: 意图分类（基于重写后的完整语义）
        IntentType intent = IntentType.TASK; // 缺省走完整 ReAct
        if (intentClassifier != null) {
            intent = intentClassifier.classify(processedPrompt);
            log.info("[{}] 意图分类结果：{}", name, intent);
        }
        return new ProcessedPrompt(intent, processedPrompt);
    }

    /**
     * 意图路由分发：每种意图对应一个 handler，逻辑高内聚。
     */
    private void dispatchByIntent(ProcessedPrompt processed, OutputSink sink) {
        switch (processed.intent()) {
            case REJECT    -> handleRejectIntent(sink);
            case CHAT      -> handleChatIntent(processed.prompt(), sink);
            case KNOWLEDGE -> handleKnowledgeIntent(processed.prompt(), sink);
            case TASK      -> handleTaskIntent(processed.prompt(), sink);
        }
    }

    // ===================================================================
    //                         意图 Handler
    // ===================================================================

    /**
     * REJECT 意图：直接返回拒绝话术，不调用任何 LLM。
     */
    private void handleRejectIntent(OutputSink sink) {
        log.info("[{}] REJECT 路由：返回拒绝话术", name);
        state = AgentState.FINISHED;
        sink.send(REJECT_REPLY);
    }

    /**
     * CHAT 意图：闲聊场景一次性直答，不进入 ReAct，不调用工具。
     */
    private void handleChatIntent(String processedPrompt, OutputSink sink) {
        log.info("[{}] CHAT 路由：一次性直答", name);
        String chatSystemPrompt = systemPrompt
                + "\n当前为闲聊模式，请用专业、亲切的语言友好回复，无需调用任何工具。";
        String answer = chatClient.prompt()
                .system(chatSystemPrompt)
                .user(processedPrompt)
                .call()
                .content();
        state = AgentState.FINISHED;
        sink.send(answer);
    }

    /**
     * KNOWLEDGE 意图：跳过 ReAct，直接走 RAG（Hybrid Search + Rerank）+ LLM 生成。
     * 工具未注入或调用异常时，自动降级到 TASK 完整 ReAct 循环。
     */
    private void handleKnowledgeIntent(String processedPrompt, OutputSink sink) {
        if (knowledgeBaseQueryTool == null) {
            log.warn("[{}] knowledgeBaseQueryTool 未注入，降级走 ReAct 循环", name);
            handleTaskIntent(processedPrompt, sink);
            return;
        }
        try {
            // 1. 检索知识库
            String ragContext = knowledgeBaseQueryTool.queryKnowledgeBase(processedPrompt);
            log.info("[{}] RAG 检索完成，上下文长度：{}", name, ragContext.length());

            // 2. 构造带知识库上下文的 Prompt
            String knowledgePrompt = String.format("""
                    请基于以下知识库检索结果回答用户问题。如果检索结果中没有相关信息，请如实告知用户。
                    
                    【知识库检索结果】
                    %s
                    
                    【用户问题】
                    %s
                    """, ragContext, processedPrompt);

            // 3. LLM 直答
            String answer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(knowledgePrompt)
                    .call()
                    .content();
            log.info("[{}] KNOWLEDGE 直接回答完成", name);
            state = AgentState.FINISHED;
            sink.send(answer);
        } catch (Exception e) {
            log.error("[{}] KNOWLEDGE 直接回答异常，降级走 ReAct 循环", name, e);
            handleTaskIntent(processedPrompt, sink);
        }
    }

    /**
     * TASK 意图：完整 ReAct 循环，全工具可用。
     */
    private void handleTaskIntent(String processedPrompt, OutputSink sink) {
        log.info("[{}] TASK 路由：进入 ReAct 循环（maxSteps={}）", name, maxSteps);
        // 用户问题入消息上下文（驱动 ReAct）
        messageList.add(new UserMessage(processedPrompt));

        for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
            currentStep = i + 1;
            log.info("Executing step {}/{}", currentStep, maxSteps);
            String stepResult = step();
            String result = "Step " + currentStep + ": " + stepResult;
            sink.send(result);
        }
        // 达到上限仍未结束，强制收尾
        if (currentStep >= maxSteps && state != AgentState.FINISHED) {
            state = AgentState.FINISHED;
            sink.send("执行结束：达到最大步骤（" + maxSteps + "）");
        }
    }

    // ===================================================================
    //                            扩展点
    // ===================================================================

    /** 子类实现 ReAct 单步逻辑 */
    public abstract String step();

    /** 清理资源（子类可覆盖） */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }

    // ===================================================================
    //                          内部数据载体
    // ===================================================================

    /** preProcess 返回值：意图类型 + 处理后的 prompt */
    private record ProcessedPrompt(IntentType intent, String prompt) {
    }
}
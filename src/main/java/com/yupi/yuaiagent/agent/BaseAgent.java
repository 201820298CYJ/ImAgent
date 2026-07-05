package com.yupi.yuaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.agent.model.AgentState;
import com.yupi.yuaiagent.agent.model.ClassifyResult;
import com.yupi.yuaiagent.agent.model.IntentType;
import com.yupi.yuaiagent.agent.sink.BufferedOutputSink;
import com.yupi.yuaiagent.agent.sink.OutputSink;
import com.yupi.yuaiagent.agent.sink.SseOutputSink;
import com.yupi.yuaiagent.chatmemory.MemoryCompressor;
import com.yupi.yuaiagent.harness.TraceCollector;
import com.yupi.yuaiagent.harness.TraceContext;
import com.yupi.yuaiagent.rag.QueryRewriter;
import com.yupi.yuaiagent.tools.KnowledgeBaseQueryTool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 * <p>
 * 执行链路：查询重写 → 意图分类（含置信度）→ 置信度门控 → 按意图路由到对应 handler。
 * <pre>
 *   run / runStream
 *     ├─ execute(prompt, sink)
 *     │    ├─ preProcess  → (intent, confidence, runnerUp, processedPrompt)
 *     │    ├─ confidence &lt; threshold → handleLowConfidence（澄清追问）
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

    /** 记忆压缩器（写入时判断是否需要压缩） */
    private MemoryCompressor memoryCompressor;

    /** 查询重写器（多轮对话上下文感知） */
    private QueryRewriter queryRewriter;
    /** 意图分类器（驱动路由分发） */
    private IntentClassifier intentClassifier;
    /** 知识库查询工具（KNOWLEDGE 意图直接调用 RAG 链路） */
    private KnowledgeBaseQueryTool knowledgeBaseQueryTool;

    /** 请求级追踪收集器（由 AgentHarness 注入，可选） */
    private TraceCollector traceCollector;

    /** 意图分类置信度阈值：低于此值触发澄清流程 */
    private double classifyConfidenceThreshold = 0.6;

    /** 当前请求的输出通道，供子类（如 ToolCallAgent）在 think/act 内部推送流式内容 */
    private OutputSink currentSink;

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

    /**
     * 使用外部提供的 SseEmitter 流式运行代理。
     * 由调用方（如 AgentHarness）负责创建 emitter，
     * onFinish 在 Agent 执行流程结束后（finally 中）同步调用，确保 Trace 不丢失。
     */
    public void runStreamWithEmitter(String userPrompt, SseEmitter sseEmitter, Runnable onFinish) {
        SseOutputSink sink = new SseOutputSink(sseEmitter);
        CompletableFuture.runAsync(() -> {
            try {
                execute(userPrompt, sink);
            } finally {
                if (onFinish != null) {
                    try {
                        onFinish.run();
                    } catch (Exception e) {
                        log.warn("onFinish 回调执行失败", e);
                    }
                }
            }
        });
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
        this.currentSink = sink;
        // 设置 ThreadLocal 追踪上下文（SSE 模式下此方法在 CompletableFuture.runAsync 线程中执行）
        if (traceCollector != null) {
            TraceContext.set(traceCollector);
        }
        try {
            // 2. 前置处理（查询重写 + 意图分类）
            ProcessedPrompt processed = preProcess(userPrompt);

            // 3. 置信度门控：低于阈值时触发澄清，否则按意图路由分发
            if (processed.confidence() < classifyConfidenceThreshold) {
                handleLowConfidence(processed, userPrompt, sink);
            } else {
                dispatchByIntent(processed, sink);
            }

            // 4. 标记成功完成
            sink.complete();
        } catch (Exception e) {
            this.state = AgentState.ERROR;
            log.error("[{}] 执行异常", name, e);
            sink.completeWithError("执行错误：" + e.getMessage());
        } finally {
            TraceContext.clear();
            this.currentSink = null;
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
        if (traceCollector != null) {
            traceCollector.setRewrittenQuery(processedPrompt);
        }

        // Step 2: 意图分类（基于重写后的完整语义）
        IntentType intent = IntentType.TASK; // 缺省走完整 ReAct
        double confidence = 1.0;
        IntentType runnerUp = null;
        if (intentClassifier != null) {
            ClassifyResult classifyResult = intentClassifier.classify(processedPrompt);
            intent = classifyResult.intent();
            confidence = classifyResult.confidence();
            runnerUp = classifyResult.runnerUp();
            log.info("[{}] 意图分类结果：{}, 置信度：{}, 候选：{}", name, intent, confidence, runnerUp);
        }
        if (traceCollector != null) {
            traceCollector.setIntent(intent);
            traceCollector.setConfidence(confidence);
        }
        return new ProcessedPrompt(intent, confidence, runnerUp, processedPrompt);
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

    /**
     * 置信度不足时的澄清处理：告知用户分类器的不确定性，请求更明确的输入。
     * 不路由到任何意图 handler，当前请求在此终结。
     * 用户的下一条消息会重新走完整的 classify 流程。
     */
    private void handleLowConfidence(ProcessedPrompt processed, String originalPrompt, OutputSink sink) {
        log.info("[{}] 置信度不足（{} < {}），触发澄清流程",
                name, processed.confidence(), classifyConfidenceThreshold);

        StringBuilder clarification = new StringBuilder();
        clarification.append("抱歉，我不太确定您的意图。");

        String topLabel = intentLabel(processed.intent());
        if (processed.runnerUp() != null) {
            String runnerUpLabel = intentLabel(processed.runnerUp());
            clarification.append(String.format("您的问题可能是想**%s**，也可能是想**%s**。", topLabel, runnerUpLabel));
        } else {
            clarification.append(String.format("您的问题可能是想**%s**，但我不够确定。", topLabel));
        }
        clarification.append("\n能否请您更具体地描述一下需求？");

        state = AgentState.FINISHED;
        sink.send(clarification.toString());
        persistEssentialMemory(originalPrompt, clarification.toString());
    }

    private static String intentLabel(IntentType type) {
        return switch (type) {
            case CHAT      -> "日常闲聊";
            case KNOWLEDGE -> "了解学院相关信息（专业、师资、招生等）";
            case TASK      -> "执行具体任务（搜索、生成文件等）";
            case REJECT    -> "超出服务范围的问题";
        };
    }

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
     * 使用流式接口，逐 token 推送到前端。
     */
    private void handleChatIntent(String processedPrompt, OutputSink sink) {
        log.info("[{}] CHAT 路由：一次性直答（流式）", name);
        String chatSystemPrompt = systemPrompt
                + "\n当前为闲聊模式，请用专业、亲切的语言友好回复，无需调用任何工具。";
        String answer = streamAndCollect(chatClient.prompt()
                .system(chatSystemPrompt)
                .user(processedPrompt), sink);
        if (traceCollector != null) {
            traceCollector.setFinalAnswer(answer);
        }
        state = AgentState.FINISHED;
        // 精华写入 Redis：只存用户问题 + 最终回答
        persistEssentialMemory(processedPrompt, answer);
    }

    /**
     * KNOWLEDGE 意图：跳过 ReAct，直接走 RAG（Hybrid Search + Rerank）+ LLM 流式生成。
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

            // 3. LLM 流式直答
            String answer = streamAndCollect(chatClient.prompt()
                    .system(systemPrompt)
                    .user(knowledgePrompt), sink);
            log.info("[{}] KNOWLEDGE 直接回答完成", name);
            if (traceCollector != null) {
                traceCollector.setFinalAnswer(answer);
            }
            state = AgentState.FINISHED;
            // 精华写入 Redis：只存用户问题 + 最终回答
            persistEssentialMemory(processedPrompt, answer);
        } catch (Exception e) {
            log.error("[{}] KNOWLEDGE 直接回答异常，降级走 ReAct 循环", name, e);
            handleTaskIntent(processedPrompt, sink);
        }
    }

    /**
     * 阻塞式订阅 ChatClient 的 stream 输出：每收到一个非空 chunk 立即推给 sink，
     * 同时聚合成完整回答返回给调用方（用于写入 Trace / 记忆）。
     */
    private String streamAndCollect(ChatClient.ChatClientRequestSpec spec, OutputSink sink) {
        StringBuilder buffer = new StringBuilder();
        spec.stream()
                .content()
                .toStream()
                .forEach(chunk -> {
                    if (chunk == null || chunk.isEmpty()) {
                        return;
                    }
                    buffer.append(chunk);
                    sink.send(chunk);
                });
        return buffer.toString();
    }

    /**
     * TASK 意图：完整 ReAct 循环，全工具可用。
     * think() 阶段的 LLM 文本由 ToolCallAgent 内部逐 chunk 推送；
     * 这里只在每步边界推送一条简短的步骤标记，方便前端渲染分段。
     */
    private void handleTaskIntent(String processedPrompt, OutputSink sink) {
        log.info("[{}] TASK 路由：进入 ReAct 循环（maxSteps={}）", name, maxSteps);
        // 用户问题入消息上下文（驱动 ReAct）
        messageList.add(new UserMessage(processedPrompt));

        for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
            currentStep = i + 1;
            log.info("Executing step {}/{}", currentStep, maxSteps);
            sink.send("\n\n[Step " + currentStep + "] ");
            step();
        }
        // 达到上限仍未结束，强制收尾
        if (currentStep >= maxSteps && state != AgentState.FINISHED) {
            state = AgentState.FINISHED;
            sink.send("\n\n执行结束：达到最大步骤（" + maxSteps + "）");
        }
        // 精华写入 Redis：只存用户问题 + 最终回答摘要（从 messageList 中提取最后的 Assistant 文本）
        String finalAnswer = extractFinalAnswer();
        if (traceCollector != null) {
            traceCollector.setFinalAnswer(finalAnswer);
        }
        persistEssentialMemory(processedPrompt, finalAnswer);
    }

    // ===================================================================
    //                      记忆持久化（精华写入）
    // ===================================================================

    /**
     * 将"用户问题 + 最终回答"精华写入 Redis，供下次请求作为历史上下文。
     * <p>
     * 只持久化结论性信息，不存 ReAct 中间过程（nextStepPrompt、工具调用详情等），
     * 避免 Redis 膨胀和下次请求注入无用噪声。
     * <p>
     * 写入前通过 {@link MemoryCompressor} 检查 Token 预算，超预算时自动压缩早期历史。
     *
     * @param userQuestion 用户原始问题（重写后）
     * @param finalAnswer  最终回答内容
     */
    private void persistEssentialMemory(String userQuestion, String finalAnswer) {
        if (StrUtil.isBlank(conversationId)) {
            return;
        }
        try {
            List<Message> essential = List.of(
                   new UserMessage(userQuestion),
                    new AssistantMessage(finalAnswer != null ? finalAnswer : "")
            );
            if (memoryCompressor != null) {
                memoryCompressor.addWithCompaction(conversationId, essential);
            } else if (chatMemory != null) {
                chatMemory.add(conversationId, essential);
            }
            log.debug("[{}] 精华写入 Redis 完成，conversationId={}", name, conversationId);
        } catch (Exception e) {
            log.warn("[{}] 精华写入 Redis 失败，不影响当前请求: {}", name, e.getMessage());
        }
    }

    /**
     * 从 messageList 中提取最终的 Assistant 回复文本（TASK 意图专用）。
     * 倒序遍历，找到最后一条有实际文本内容的 AssistantMessage。
     */
    private String extractFinalAnswer() {
        for (int i = messageList.size() - 1; i >= 0; i--) {
            Message msg = messageList.get(i);
            if (msg instanceof AssistantMessage assistantMsg) {
                String text = assistantMsg.getText();
                if (StrUtil.isNotBlank(text)) {
                    return text;
                }
            }
        }
        return "任务已完成";
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

    /** preProcess 返回值：意图类型 + 置信度 + 候选意图 + 处理后的 prompt */
    private record ProcessedPrompt(IntentType intent, double confidence, IntentType runnerUp, String prompt) {
    }
}
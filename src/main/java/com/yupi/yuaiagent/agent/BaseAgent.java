package com.yupi.yuaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.agent.model.AgentState;
import com.yupi.yuaiagent.agent.model.IntentType;
import com.yupi.yuaiagent.rag.QueryRewriter;
import com.yupi.yuaiagent.tools.KnowledgeBaseQueryTool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 * <p>
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 支持查询重写（上下文感知）和意图分类（动态调整 Agent 策略）。
 * 子类必须实现step方法。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    // 核心属性
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

    // 对话记忆组件
    private ChatMemory chatMemory;
    // 会话 ID
    private String conversationId;

    // 查询重写器（多轮对话上下文感知）
    private QueryRewriter queryRewriter;
    // 意图分类器（动态调整 Agent 行为策略）
    private IntentClassifier intentClassifier;
    // 自适应记忆压缩器（防止 messageList 无限膨胀）
    private AdaptiveMemoryCompressor memoryCompressor;
    // 知识库查询工具（KNOWLEDGE 意图时直接调用 RAG 链路）
    private KnowledgeBaseQueryTool knowledgeBaseQueryTool;
    // 上一次 preProcess 识别的意图类型
    private IntentType lastIntent;
    // 原始系统提示词（意图分类时动态拼接，需保留原始值）
    private String originalSystemPrompt;
    // 原始最大步数（意图分类时动态调整，需保留原始值）
    private int originalMaxSteps;

    /**
     * 查询重写 + 意图分类的前置处理流程
     * 流程：先查询重写补全语义 → 再意图分类动态调整策略
     *
     * @param userPrompt 用户原始输入
     * @return 处理后的 prompt，如果 REJECT 返回 null
     */
    private String preProcess(String userPrompt) {
        String processedPrompt = userPrompt;

        // Step 1: 查询重写（先补全语义，消除指代歧义）
        if (queryRewriter != null && chatMemory != null && StrUtil.isNotBlank(conversationId)) {
            processedPrompt = queryRewriter.doQueryRewrite(userPrompt, chatMemory, conversationId);
            log.info("[{}] 查询重写：{} -> {}", name, userPrompt, processedPrompt);
        }

        // Step 2: 意图分类（基于重写后的完整语义进行分类）
        if (intentClassifier != null) {
            // 首次调用时保存原始配置
            if (originalSystemPrompt == null) {
                originalSystemPrompt = systemPrompt;
            }
            if (originalMaxSteps == 0) {
                originalMaxSteps = maxSteps;
            }
            // 每次 run 都基于原始配置重新调整
            systemPrompt = originalSystemPrompt;
            maxSteps = originalMaxSteps;

            IntentType intent = intentClassifier.classify(processedPrompt);
            log.info("[{}] 意图分类结果：{}", name, intent);
            lastIntent = intent;

            switch (intent) {
                case CHAT:
                    maxSteps = 1;
                    systemPrompt = systemPrompt
                            + "\n当前为闲聊模式，直接友好回复即可，无需调用任何工具，直接调用 terminate 结束。";
                    break;
                case KNOWLEDGE:
                    // 知识库问答模式：跳过 ReAct 循环，直接走 RAG 链路
                    break;
                case TASK:
                    // 复杂任务模式：保持原始配置不变
                    break;
                case REJECT:
                    // 返回 null 表示拒绝，由调用方处理
                    return null;
            }
        }

        return processedPrompt;
    }

    /**
     * KNOWLEDGE 意图直接走 RAG 链路：检索知识库 + LLM 生成回答
     * 跳过 ReAct 循环，减少不必要的工具调用开销
     *
     * @param processedPrompt 经过查询重写后的用户问题
     * @return LLM 基于知识库上下文生成的回答
     */
    private String knowledgeDirectAnswer(String processedPrompt) {
        if (knowledgeBaseQueryTool == null) {
            log.warn("[{}] knowledgeBaseQueryTool 未注入，降级走 ReAct 循环", name);
            return null;
        }
        // 1. 调用 RAG 链路检索知识库（Hybrid Search + Rerank）
        String ragContext = knowledgeBaseQueryTool.queryKnowledgeBase(processedPrompt);
        log.info("[{}] RAG 直接检索完成，上下文长度：{}", name, ragContext.length());

        // 2. 构造带知识库上下文的 Prompt，让 LLM 生成最终回答
        String knowledgePrompt = String.format("""
                请基于以下知识库检索结果回答用户问题。如果检索结果中没有相关信息，请如实告知用户。
                
                【知识库检索结果】
                %s
                
                【用户问题】
                %s
                """, ragContext, processedPrompt);

        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(knowledgePrompt)
                .call()
                .content();
        log.info("[{}] KNOWLEDGE 直接回答完成", name);
        return answer;
    }

    /**
     * 运行代理
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public String run(String userPrompt) {
        // 1、基础校验
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }
        // 2、执行，更改状态
        this.state = AgentState.RUNNING;

        // 3、查询重写 + 意图分类前置处理
        String processedPrompt = preProcess(userPrompt);
        if (processedPrompt == null) {
            // REJECT 意图：直接返回拒绝消息
            state = AgentState.FINISHED;
            return "非常抱歉，该问题超出了我的服务范围。建议您通过南京大学信息管理学院官方渠道获取更多帮助。";
        }

        // 4、KNOWLEDGE 意图：跳过 ReAct 循环，直接走 RAG 链路
        if (lastIntent == IntentType.KNOWLEDGE) {
            try {
                String answer = knowledgeDirectAnswer(processedPrompt);
                if (answer != null) {
                    state = AgentState.FINISHED;
                    return answer;
                }
                // 降级：knowledgeBaseQueryTool 未注入，继续走 ReAct 循环
                log.info("[{}] KNOWLEDGE 直接回答降级，进入 ReAct 循环", name);
            } catch (Exception e) {
                log.error("[{}] KNOWLEDGE 直接回答异常，降级走 ReAct 循环", name, e);
            }
        }

        // 记录消息上下文（使用重写后的语句进入 ReAct 循环）
        messageList.add(new UserMessage(processedPrompt));
        // 保存结果列表
        List<String> results = new ArrayList<>();
        try {
            // 执行循环
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("Executing step {}/{}", stepNumber, maxSteps);
                // 单步执行
                String stepResult = step();
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);
                // 自适应记忆压缩：防止 messageList 在多步骤执行中无限膨胀
                compressMemoryIfNeeded();
            }
            // 检查是否超出步骤限制
            if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            return String.join("\n", results);
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("error executing agent", e);
            return "执行错误" + e.getMessage();
        } finally {
            // 4、清理资源
            this.cleanup();
        }
    }

    /**
     * 运行代理（流式输出）
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public SseEmitter runStream(String userPrompt) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter sseEmitter = new SseEmitter(300000L); // 5 分钟超时
        // 使用线程异步处理，避免阻塞主线程
        CompletableFuture.runAsync(() -> {
            // 1、基础校验
            try {
                if (this.state != AgentState.IDLE) {
                    sseEmitter.send("错误：无法从状态运行代理：" + this.state);
                    sseEmitter.complete();
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    sseEmitter.send("错误：不能使用空提示词运行代理");
                    sseEmitter.complete();
                    return;
                }
            } catch (Exception e) {
                sseEmitter.completeWithError(e);
                return;
            }
            // 2、执行，更改状态
            this.state = AgentState.RUNNING;

            // 3、查询重写 + 意图分类前置处理
            String processedPrompt = preProcess(userPrompt);
            if (processedPrompt == null) {
                // REJECT 意图：直接发送拒绝消息并结束
                state = AgentState.FINISHED;
                try {
                    sseEmitter.send("非常抱歉，该问题超出了我的服务范围。建议您通过南京大学信息管理学院官方渠道获取更多帮助。");
                    sseEmitter.complete();
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
                return;
            }

            // 4、KNOWLEDGE 意图：跳过 ReAct 循环，直接走 RAG 链路
            if (lastIntent == IntentType.KNOWLEDGE) {
                try {
                    String answer = knowledgeDirectAnswer(processedPrompt);
                    if (answer != null) {
                        state = AgentState.FINISHED;
                        sseEmitter.send(answer);
                        sseEmitter.complete();
                        return;
                    }
                    // 降级：knowledgeBaseQueryTool 未注入，继续走 ReAct 循环
                    log.info("[{}] KNOWLEDGE 直接回答降级，进入 ReAct 循环", name);
                } catch (Exception e) {
                    log.error("[{}] KNOWLEDGE 直接回答异常，降级走 ReAct 循环", name, e);
                }
            }

            // 记录消息上下文（使用重写后的语句进入 ReAct 循环）
            messageList.add(new UserMessage(processedPrompt));
            // 保存结果列表
            List<String> results = new ArrayList<>();
            try {
                // 执行循环
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("Executing step {}/{}", stepNumber, maxSteps);
                    // 单步执行
                    String stepResult = step();
                    String result = "Step " + stepNumber + ": " + stepResult;
                    results.add(result);
                    // 输出当前每一步的结果到 SSE
                    sseEmitter.send(result);
                    // 自适应记忆压缩：防止 messageList 在多步骤执行中无限膨胀
                    compressMemoryIfNeeded();
                }
                // 检查是否超出步骤限制
                if (currentStep >= maxSteps) {
                    state = AgentState.FINISHED;
                    results.add("Terminated: Reached max steps (" + maxSteps + ")");
                    sseEmitter.send("执行结束：达到最大步骤（" + maxSteps + "）");
                }
                // 正常完成
                sseEmitter.complete();
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                try {
                    sseEmitter.send("执行错误：" + e.getMessage());
                    sseEmitter.complete();
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
            } finally {
                // 4、清理资源
                this.cleanup();
            }
        });

        // 设置超时回调
        sseEmitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSE connection timeout");
        });
        // 设置完成回调
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
     * 定义单个步骤
     *
     * @return
     */
    public abstract String step();

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }

    /**
     * 自适应记忆压缩：在每个 step 执行后检查 messageList 是否需要压缩
     */
    private void compressMemoryIfNeeded() {
        if (memoryCompressor != null) {
            List<Message> compressed = memoryCompressor.compressIfNeeded(messageList);
            if (compressed != messageList) {
                // 压缩发生了，替换 messageList
                messageList = new ArrayList<>(compressed);
            }
        }
    }
}
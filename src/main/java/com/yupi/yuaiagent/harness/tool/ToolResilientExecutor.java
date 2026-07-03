package com.yupi.yuaiagent.harness.tool;

import com.yupi.yuaiagent.harness.TraceCollector;
import com.yupi.yuaiagent.harness.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具韧性执行层（Tool Resilient Executor）。
 * <p>
 * 基于 JDK 21 虚拟线程构建，负责三件事：
 * <ol>
 *     <li><b>多工具并行调度</b>：一轮 LLM 返回的多个工具调用（如 queryKnowledgeBase + searchWeb）
 *         同时提交到虚拟线程执行器，复合请求耗时从 Σ 降到 max</li>
 *     <li><b>工具级差异化超时</b>：每个工具按 {@link ToolPolicy} 独立超时，避免统一阈值误杀</li>
 *     <li><b>异常统一回喂 LLM</b>：所有异常都通过 {@link ToolFailureFormatter} 转为
 *         "原因 + 建议" 的中文反馈，作为 {@link ToolResponseMessage.ToolResponse} 塞回消息上下文，
 *         驱动 LLM 在下一轮 think() 中自我纠偏，而非中断 ReAct 循环</li>
 * </ol>
 * <b>虚拟线程与 ThreadLocal 传播</b>：{@link TraceContext} 是父线程的 ThreadLocal，
 * 虚拟线程默认不继承。为保证深层组件（如 KnowledgeBaseQueryTool）仍能上报 Trace，
 * 提交任务前显式抓取当前 TraceCollector 引用，在子任务内部重新 set + finally clear。
 */
@Component
@Slf4j
public class ToolResilientExecutor {

    /** 虚拟线程执行器：IO 阻塞时自动解绑载体线程，天然适合工具调用（HTTP/PG/ES） */
    private final ExecutorService virtualThreadExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 并行执行一批工具调用。
     * <p>
     * 每个工具在独立的虚拟线程中执行，独立计时、独立超时，异常不会向外抛出——
     * 而是被结构化为中文反馈作为该工具的 responseData 返回。
     *
     * @param toolCalls      LLM 决定要调用的工具列表
     * @param availableTools 所有已注册的工具回调
     * @return 与 toolCalls 顺序对齐的 ToolResponse 列表（无论成功失败）
     */
    public List<ToolResponseMessage.ToolResponse> executeAll(
            List<AssistantMessage.ToolCall> toolCalls,
            ToolCallback[] availableTools) {

        // 快照当前线程的 TraceCollector，传给子任务重新 set 到虚拟线程的 ThreadLocal
        TraceCollector parentTrace = TraceContext.get();

        List<CompletableFuture<ToolResponseMessage.ToolResponse>> futures = toolCalls.stream()
                .map(tc -> executeOne(tc, availableTools, parentTrace))
                .toList();

        // 等所有任务完成（每个任务内部已 handle 异常，不会 join 抛错）
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream().map(CompletableFuture::join).toList();
    }

    /**
     * 单个工具调用的完整韧性包装：虚拟线程 + 超时 + 异常统一转反馈。
     */
    private CompletableFuture<ToolResponseMessage.ToolResponse> executeOne(
            AssistantMessage.ToolCall toolCall,
            ToolCallback[] availableTools,
            TraceCollector parentTrace) {

        ToolPolicy policy = ToolPolicy.of(toolCall.name());
        long start = System.currentTimeMillis();

        return CompletableFuture
                .supplyAsync(() -> {
                    // 虚拟线程内部：显式恢复 ThreadLocal，让 KnowledgeBaseQueryTool 等深层组件能上报 Trace
                    if (parentTrace != null) {
                        TraceContext.set(parentTrace);
                    }
                    try {
                        return invokeTool(toolCall, availableTools);
                    } finally {
                        TraceContext.clear();
                    }
                }, virtualThreadExecutor)
                .orTimeout(policy.timeoutMs(), TimeUnit.MILLISECONDS)
                .handle((result, ex) -> {
                    long durationMs = System.currentTimeMillis() - start;
                    if (ex == null) {
                        log.info("[Resilient] 工具 {} 执行成功，耗时 {}ms", toolCall.name(), durationMs);
                        return new ToolResponseMessage.ToolResponse(
                                toolCall.id(), toolCall.name(), result);
                    }
                    // 关键：异常不抛出，转为 LLM 可理解的中文反馈
                    ToolErrorType type = classify(ex);
                    String feedback = ToolFailureFormatter.format(toolCall.name(), type, ex, policy);
                    log.warn("[Resilient] 工具 {} 失败[{}]，耗时 {}ms，反馈已回喂 LLM: {}",
                            toolCall.name(), type, durationMs, feedback);
                    return new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(), feedback);
                });
    }

    /**
     * 定位并调用工具。工具名未找到直接抛 IllegalArgumentException，由外层归类为 INVALID_ARG。
     */
    private String invokeTool(AssistantMessage.ToolCall toolCall, ToolCallback[] availableTools) {
        ToolCallback callback = Arrays.stream(availableTools)
                .filter(cb -> cb.getToolDefinition().name().equals(toolCall.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "未注册的工具：" + toolCall.name()));
        String args = toolCall.arguments();
        if (args == null || args.isBlank()) {
            throw new IllegalArgumentException("工具参数为空");
        }
        return callback.call(args);
    }

    /**
     * 异常分类：
     * <ul>
     *     <li>TimeoutException / CancellationException → TIMEOUT</li>
     *     <li>IllegalArgumentException / NullPointerException → INVALID_ARG</li>
     *     <li>其他 → EXECUTION_ERROR</li>
     * </ul>
     */
    private ToolErrorType classify(Throwable ex) {
        Throwable root = rootCause(ex);
        if (root instanceof TimeoutException || ex instanceof TimeoutException) {
            return ToolErrorType.TIMEOUT;
        }
        if (root instanceof IllegalArgumentException || root instanceof NullPointerException) {
            return ToolErrorType.INVALID_ARG;
        }
        return ToolErrorType.EXECUTION_ERROR;
    }

    private Throwable rootCause(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    @PreDestroy
    public void shutdown() {
        virtualThreadExecutor.shutdown();
    }
}

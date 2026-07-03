package com.yupi.yuaiagent.harness;

import com.yupi.yuaiagent.agent.model.IntentType;
import com.yupi.yuaiagent.harness.model.AgentRunTrace;
import com.yupi.yuaiagent.harness.model.AgentRunTrace.RetrievalEntry;
import com.yupi.yuaiagent.harness.model.AgentRunTrace.ToolCallEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 请求级追踪收集器（线程安全）
 * <p>
 * 由 AgentHarness 创建并注入 Agent，各子系统通过 {@link TraceContext} 获取并上报执行细节。
 * 使用 CopyOnWriteArrayList + volatile 保证 SSE 异步线程与回调线程间的可见性。
 */
public class TraceCollector {

    private final String traceId;
    private final String conversationId;
    private final String userQuery;
    private final Instant startTime;

    private volatile String rewrittenQuery;
    private volatile IntentType intent;
    private volatile double confidence = -1.0;
    private volatile String finalAnswer;
    private volatile Instant endTime;

    private final CopyOnWriteArrayList<RetrievalEntry> retrievalContext = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RetrievalEntry> rerankContext = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ToolCallEntry> toolCalls = new CopyOnWriteArrayList<>();

    public TraceCollector(String traceId, String conversationId, String userQuery, Instant startTime) {
        this.traceId = traceId;
        this.conversationId = conversationId;
        this.userQuery = userQuery;
        this.startTime = startTime;
    }

    public void setRewrittenQuery(String rewrittenQuery) {
        this.rewrittenQuery = rewrittenQuery;
    }

    public void setIntent(IntentType intent) {
        this.intent = intent;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public void addRetrievalEntry(RetrievalEntry entry) {
        this.retrievalContext.add(entry);
    }

    public void addRerankEntry(RetrievalEntry entry) {
        this.rerankContext.add(entry);
    }

    public void addToolCall(ToolCallEntry entry) {
        this.toolCalls.add(entry);
    }

    public void markEnd() {
        this.endTime = Instant.now();
    }

    public AgentRunTrace buildTrace() {
        Instant end = endTime != null ? endTime : Instant.now();
        long durationMs = Duration.between(startTime, end).toMillis();
        int tokenEstimate = estimateTokens();

        return new AgentRunTrace(
                traceId, conversationId, userQuery, rewrittenQuery,
                intent, confidence,
                List.copyOf(retrievalContext),
                List.copyOf(rerankContext),
                List.copyOf(toolCalls),
                finalAnswer,
                tokenEstimate, durationMs, startTime
        );
    }

    private int estimateTokens() {
        int chars = 0;
        if (userQuery != null) chars += userQuery.length();
        if (rewrittenQuery != null) chars += rewrittenQuery.length();
        if (finalAnswer != null) chars += finalAnswer.length();
        for (ToolCallEntry tc : toolCalls) {
            if (tc.arguments() != null) chars += tc.arguments().length();
            if (tc.result() != null) chars += tc.result().length();
        }
        return (int) (chars * 1.5);
    }
}

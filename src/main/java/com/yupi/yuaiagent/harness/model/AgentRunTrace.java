package com.yupi.yuaiagent.harness.model;

import com.yupi.yuaiagent.agent.model.IntentType;

import java.time.Instant;
import java.util.List;

/**
 * 单次 Agent 请求的完整执行追踪记录
 */
public record AgentRunTrace(
        String traceId,
        String conversationId,
        String userQuery,
        String rewrittenQuery,
        IntentType intent,
        double confidence,
        List<RetrievalEntry> retrievalContext,
        List<RetrievalEntry> rerankContext,
        List<ToolCallEntry> toolCalls,
        String finalAnswer,
        int tokenEstimate,
        long durationMs,
        Instant timestamp
) {
    /** 检索条目 */
    public record RetrievalEntry(
            String documentId,
            String snippet,
            double score,
            String source
    ) {}

    /** 工具调用条目 */
    public record ToolCallEntry(
            String toolName,
            String arguments,
            String result,
            long durationMs
    ) {}
}

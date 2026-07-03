package com.yupi.yuaiagent.harness;

import com.yupi.yuaiagent.agent.IntentClassifier;
import com.yupi.yuaiagent.agent.YuManus;
import com.yupi.yuaiagent.chatmemory.MemoryCompressor;
import com.yupi.yuaiagent.chatmemory.RedisChatMemory;
import com.yupi.yuaiagent.harness.model.AgentRunTrace;
import com.yupi.yuaiagent.harness.tool.ToolResilientExecutor;
import com.yupi.yuaiagent.rag.QueryRewriter;
import com.yupi.yuaiagent.tools.KnowledgeBaseQueryTool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.UUID;

/**
 * Agent Harness 编排层
 * <p>
 * 将请求级生命周期管理从 Controller 提升到独立组件，实现关注点分离：
 * <ol>
 *     <li>创建请求级 TraceCollector</li>
 *     <li>实例化 YuManus 并注入追踪上下文</li>
 *     <li>委托 Agent 执行（保持原有 SSE 流程不变）</li>
 *     <li>通过 SseEmitter 生命周期回调收集并存储 Trace</li>
 * </ol>
 */
@Component
@Slf4j
public class AgentHarness {

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    @Resource
    private QueryRewriter queryRewriter;

    @Resource
    private IntentClassifier intentClassifier;

    @Resource
    private KnowledgeBaseQueryTool knowledgeBaseQueryTool;

    @Resource
    private RedisChatMemory redisChatMemory;

    @Resource
    private MemoryCompressor memoryCompressor;

    @Resource
    private TraceStore traceStore;

    @Resource
    private ToolResilientExecutor toolResilientExecutor;

    @Value("${agent.classify.confidence-threshold:0.6}")
    private double classifyConfidenceThreshold;

    /**
     * 流式运行代理（带全链路追踪）
     */
    public SseEmitter runStream(String message, String chatId) {
        TraceCollector traceCollector = createTraceCollector(message, chatId);

        YuManus yuManus = buildAgent(chatId, traceCollector);
        SseEmitter emitter = yuManus.runStream(message);

        emitter.onCompletion(() -> finalizeTrace(traceCollector));
        emitter.onTimeout(() -> finalizeTrace(traceCollector));

        return emitter;
    }

    /**
     * 同步运行代理（带全链路追踪）
     */
    public String run(String message, String chatId) {
        TraceCollector traceCollector = createTraceCollector(message, chatId);

        YuManus yuManus = buildAgent(chatId, traceCollector);
        String result = yuManus.run(message);

        finalizeTrace(traceCollector);
        return result;
    }

    private TraceCollector createTraceCollector(String message, String chatId) {
        return new TraceCollector(
                UUID.randomUUID().toString(),
                chatId,
                message,
                Instant.now()
        );
    }

    private YuManus buildAgent(String chatId, TraceCollector traceCollector) {
        YuManus yuManus = new YuManus(allTools, dashscopeChatModel, redisChatMemory,
                memoryCompressor, queryRewriter, intentClassifier, knowledgeBaseQueryTool, chatId);
        yuManus.setConversationId(chatId);
        yuManus.setTraceCollector(traceCollector);
        yuManus.setToolResilientExecutor(toolResilientExecutor);
        yuManus.setClassifyConfidenceThreshold(classifyConfidenceThreshold);
        return yuManus;
    }

    private void finalizeTrace(TraceCollector traceCollector) {
        try {
            traceCollector.markEnd();
            AgentRunTrace trace = traceCollector.buildTrace();
            traceStore.add(trace);
            log.info("[Harness] Trace 已记录: traceId={}, intent={}, durationMs={}",
                    trace.traceId(), trace.intent(), trace.durationMs());
        } catch (Exception e) {
            log.warn("[Harness] Trace 记录失败", e);
        }
    }
}

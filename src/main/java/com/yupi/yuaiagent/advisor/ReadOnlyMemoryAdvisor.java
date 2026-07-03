package com.yupi.yuaiagent.advisor;

import com.yupi.yuaiagent.chatmemory.RedisChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读记忆注入 Advisor
 * <p>
 * 只在请求前从 Redis 读取历史并注入到 messages 头部，
 * <strong>不在响应后回写</strong>——回写由 BaseAgent 在意图处理结束后手动控制，
 * 只写入"用户原始问题 + 最终回答"精华，避免 ReAct 中间过程污染持久化记忆。
 */
@Slf4j
public class ReadOnlyMemoryAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    private static final int DEFAULT_LAST_N = 20;

    private final RedisChatMemory redisChatMemory;
    private final String conversationId;
    private final int lastN;

    public ReadOnlyMemoryAdvisor(RedisChatMemory redisChatMemory, String conversationId) {
        this(redisChatMemory, conversationId, DEFAULT_LAST_N);
    }

    public ReadOnlyMemoryAdvisor(RedisChatMemory redisChatMemory, String conversationId, int lastN) {
        this.redisChatMemory = redisChatMemory;
        this.conversationId = conversationId;
        this.lastN = lastN;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        // 高优先级，确保在压缩 Advisor 之前注入历史
        return -2000;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        return chain.nextAroundCall(injectHistory(advisedRequest));
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        return chain.nextAroundStream(injectHistory(advisedRequest));
    }

    /**
     * 从 Redis 读取历史消息并注入到 messages 头部。
     * 只读操作，不做任何回写。
     */
    private AdvisedRequest injectHistory(AdvisedRequest request) {
        List<Message> history = redisChatMemory.get(conversationId, lastN);
        if (history.isEmpty()) {
            return request;
        }
        // 将历史消息放在当前 messages 前面
        List<Message> merged = new ArrayList<>(history.size() + request.messages().size());
        merged.addAll(history);
        merged.addAll(request.messages());

        log.debug("[ReadOnlyMemoryAdvisor] 注入 {} 条历史消息，conversationId={}", history.size(), conversationId);
        return AdvisedRequest.from(request).messages(merged).build();
    }
}
package com.yupi.yuaiagent.advisor;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.yuaiagent.chatmemory.RedisChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Token 感知的自适应记忆压缩 Advisor
 * <p>
 * 作为 Spring AI 的拦截器（Advisor），在请求到达 LLM 之前对消息列表执行
 * <strong>Token 预算感知 + 动态压缩</strong>，并将压缩结果<strong>同步回写 Redis</strong>
 * 实现"存储级压缩"：
 * <ul>
 *     <li>会话记忆 Token 预算 = {@link #MAX_MEMORY_TOKENS}（8000），
 *         基于实际场景推算：混合对话约 800 token/轮，目标保留 10 轮上下文</li>
 *     <li>当消息列表估算 token 超过预算时，从最早的消息开始动态计算切割点，
 *         将超出部分压缩为 1 条 SystemMessage 摘要</li>
 *     <li>压缩后的消息列表通过 {@link RedisChatMemory#replace} 原子回写 Redis</li>
 * </ul>
 * <p>
 * 摘要使用轻量模型（qwen-turbo）<strong>异步生成</strong>，设置超时上限，
 * 降低对主调用链的延迟影响；摘要失败或超时时降级为简单截断，保证调用链不中断。
 * <p>
 * <b>注意</b>：本类为非单例设计，每个会话独立实例化，持有 conversationId 进行精确回写。
 */
@Slf4j
public class AdaptiveMemoryCompressorAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    /**
     * 会话记忆 Token 预算上限。
     * 推算依据（qwen-plus，100 万上下文窗口，压缩目的是控制成本与噪声）：
     * <ul>
     *     <li>纯对话轮次：~400 token/轮</li>
     *     <li>带工具/RAG 轮次：~1500-2500 token/轮（工具返回 + 助手回复会被 Advisor 写入 Redis）</li>
     *     <li>混合场景加权中位数：~800 token/轮</li>
     *     <li>目标保留 10 轮上下文 → 800 × 10 = 8000</li>
     * </ul>
     */
    private static final int MAX_MEMORY_TOKENS = 8000;

    /** Token 估算系数：中文 1 字符 ≈ 1.5 token（覆盖中英文混合场景的粗略估算） */
    private static final double TOKEN_PER_CHAR = 1.5;

    /**
     * 压缩缓冲比例：触发压缩时，额外多压缩 30% 预算量的 token，
     * 避免压缩后刚好在阈值边缘、下一轮立刻再次触发压缩。
     * <p>
     * 例：预算 8000，缓冲 = 8000 × 0.3 = 2400，压缩目标 = excess + 2400，
     * 压缩后剩余约 8000 - 2400 = 5600 token，可以承受约 3 轮新增对话才再次触发。
     */
    private static final double COMPRESS_BUFFER_RATIO = 0.3;

    /** 单条消息文本截断长度（避免摘要请求过长） */
    private static final int SINGLE_MESSAGE_TRUNCATE = 500;

    /** 用于生成摘要的轻量模型 */
    private static final String COMPRESS_MODEL = "qwen-turbo";

    /** 独立 ChatClient，避免摘要调用再次进入本 Advisor 形成递归 */
    private final ChatClient summaryChatClient;

    /** Redis 会话记忆，用于存储级回写压缩后的消息 */
    private final RedisChatMemory redisChatMemory;

    /** 当前会话 ID，用于定位 Redis 中的对应 key */
    private final String conversationId;

    public AdaptiveMemoryCompressorAdvisor(ChatModel dashscopeChatModel,
                                           RedisChatMemory redisChatMemory,
                                           String conversationId) {
        this.summaryChatClient = ChatClient.builder(dashscopeChatModel).build();
        this.redisChatMemory = redisChatMemory;
        this.conversationId = conversationId;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return -1000;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        return chain.nextAroundCall(compressIfNeeded(advisedRequest));
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        return chain.nextAroundStream(compressIfNeeded(advisedRequest));
    }

    // ===================================================================
    //                         Token 感知压缩核心
    // ===================================================================

    /**
     * Token 感知压缩核心逻辑：
     * <ol>
     *     <li>估算当前消息列表的总 token 量</li>
     *     <li>未超预算则直接放行</li>
     *     <li>超预算时，动态计算切割点——从最早消息累加 token，直到累加量 >= 超出量</li>
     *     <li>将切割出的早期消息异步压缩为一条摘要 SystemMessage</li>
     *     <li>压缩后的完整消息列表原子回写 Redis（存储级压缩）</li>
     * </ol>
     */
    private AdvisedRequest compressIfNeeded(AdvisedRequest request) {
        List<Message> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            return request;
        }

        int totalTokens = estimateTokens(messages);
        if (totalTokens <= MAX_MEMORY_TOKENS) {
            return request;
        }

        // 计算超出量 + 缓冲量，一次性压缩到阈值的 70% 左右，避免频繁触发
        int excess = totalTokens - MAX_MEMORY_TOKENS;
        int buffer = (int) (MAX_MEMORY_TOKENS * COMPRESS_BUFFER_RATIO);
        int cutTarget = excess + buffer;
        int cutIndex = findCutIndex(messages, cutTarget);

        log.info("[记忆压缩] 触发 Token 感知压缩，总 token≈{}，预算={}，超出≈{}，缓冲={}，压缩前 {} 条",
                totalTokens, MAX_MEMORY_TOKENS, excess, buffer, cutIndex);

        List<Message> earlyMessages = messages.subList(0, cutIndex);
        List<Message> remainMessages = messages.subList(cutIndex, messages.size());

        // 同步生成摘要，失败时降级为简单截断
        String summary = summarize(earlyMessages);

        List<Message> compressed = new ArrayList<>(remainMessages.size() + 1);
        compressed.add(new SystemMessage("以下是之前对话的摘要（用于保持上下文连贯性）：\n" + summary));
        compressed.addAll(remainMessages);

        int compressedTokens = estimateTokens(compressed);
        log.info("[记忆压缩] 压缩完成: {} 条(≈{} token) → {} 条(≈{} token)",
                messages.size(), totalTokens, compressed.size(), compressedTokens);

        // 存储级压缩：将压缩后的消息列表原子回写 Redis
        writeBackToRedis(compressed);

        return AdvisedRequest.from(request).messages(compressed).build();
    }

    /**
     * 估算消息列表的总 token 数。
     * 采用字符数 × 系数的粗略估算，中文 1 字 ≈ 1.5 token，足以用于预算判断。
     */
    private int estimateTokens(List<Message> messages) {
        int totalChars = 0;
        for (Message msg : messages) {
            String text = msg.getText();
            if (text != null) {
                totalChars += text.length();
            }
        }
        return (int) (totalChars * TOKEN_PER_CHAR);
    }

    /**
     * 从最早的消息开始累加 token，找到刚好能覆盖超出量的切割索引。
     * <p>
     * 保底至少切 1 条（避免死循环：消息总量超预算但每条都很小时），
     * 且至少保留最后 1 条消息不被压缩。
     *
     * @param messages 完整消息列表
     * @param excess   需要压缩掉的 token 量
     * @return 切割索引（前 cutIndex 条将被压缩为摘要）
     */
    private int findCutIndex(List<Message> messages, int excess) {
        int accumulated = 0;
        for (int i = 0; i < messages.size() - 1; i++) {
            String text = messages.get(i).getText();
            accumulated += (int) ((text != null ? text.length() : 0) * TOKEN_PER_CHAR);
            if (accumulated >= excess) {
                return i + 1;
            }
        }
        // 兜底：保留最后一条，其余全部压缩
        return Math.max(1, messages.size() - 1);
    }

    // ===================================================================
    //                         Redis 回写
    // ===================================================================

    /**
     * 将压缩后的消息列表回写 Redis，替换原全量历史。
     * 回写失败不影响当前请求（已在内存中完成压缩），仅打印 warn 日志。
     */
    private void writeBackToRedis(List<Message> compressedMessages) {
        try {
            redisChatMemory.replace(conversationId, compressedMessages);
        } catch (Exception e) {
            log.warn("[记忆压缩] 存储级回写失败，不影响当前请求: {}", e.getMessage());
        }
    }

    // ===================================================================
    //                         摘要生成
    // ===================================================================

    /**
     * 同步生成摘要，失败时降级为简单截断兜底。
     */
    private String summarize(List<Message> messages) {
        StringBuilder history = new StringBuilder();
        for (Message msg : messages) {
            String role = switch (msg.getMessageType()) {
                case USER -> "用户";
                case ASSISTANT -> "助手";
                case TOOL -> "工具返回";
                case SYSTEM -> "系统";
            };
            String text = msg.getText();
            if (text != null && text.length() > SINGLE_MESSAGE_TRUNCATE) {
                text = text.substring(0, SINGLE_MESSAGE_TRUNCATE) + "...（已截断）";
            }
            history.append(role).append("：").append(text).append("\n");
        }

        String prompt = String.format("""
                请将以下对话历史压缩为一段简洁的摘要，保留关键信息：
                1. 用户提出了什么问题/需求
                2. 助手做了哪些操作（调用了什么工具、得到了什么结果）
                3. 当前任务的进展状态
                
                要求：使用中文，只输出摘要内容。
                
                === 对话历史 ===
                %s
                """, history);

        try {
            DashScopeChatOptions options = DashScopeChatOptions.builder()
                    .withModel(COMPRESS_MODEL)
                    .withTemperature(0.0)
                    .build();

            String result = summaryChatClient.prompt()
                    .user(prompt)
                    .options(options)
                    .call()
                    .content();

            if (result != null && !result.isBlank()) {
                log.info("[记忆压缩] 摘要生成成功，长度: {} 字", result.length());
                return result.trim();
            }
        } catch (Exception e) {
            log.warn("[记忆压缩] 摘要生成失败，使用简单截断兜底: {}", e.getMessage());
        }
        return buildFallbackSummary(messages);
    }

    /**
     * 兜底摘要：LLM 摘要失败/超时时，简单保留消息文本片段。
     */
    private String buildFallbackSummary(List<Message> messages) {
        return "（摘要生成失败，以下为对话关键片段）\n" +
                messages.stream()
                        .filter(m -> m.getText() != null)
                        .map(m -> m.getText().length() > 100
                                ? m.getText().substring(0, 100) + "..." : m.getText())
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("无历史记录");
    }
}
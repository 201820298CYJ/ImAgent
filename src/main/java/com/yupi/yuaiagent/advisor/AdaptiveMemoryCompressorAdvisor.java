package com.yupi.yuaiagent.advisor;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.yuaiagent.chatmemory.RedisChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 自适应记忆压缩 Advisor
 * <p>
 * 作为 Spring AI 的拦截器（Advisor），在请求到达 LLM 之前对消息列表执行
 * <strong>滑动窗口 + 动态压缩</strong>，并将压缩结果<strong>同步回写 Redis</strong>
 * 实现"存储级压缩"：
 * <ul>
 *     <li>滑动窗口大小 = {@link #WINDOW_SIZE}（20）</li>
 *     <li>当 messages.size() >= 20 时，将前 {@link #COMPRESS_COUNT}（10）条压缩为 1 条 SystemMessage 摘要</li>
 *     <li>压缩后保留：1 条摘要 + 后 10 条原文 = 11 条，回到窗口内</li>
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

    /** 滑动窗口大小：消息数达到此值时触发压缩 */
    private static final int WINDOW_SIZE = 20;

    /** 触发压缩时，对最早的多少条消息做摘要压缩 */
    private static final int COMPRESS_COUNT = 10;

    /** 单条消息文本截断长度（避免摘要请求过长） */
    private static final int SINGLE_MESSAGE_TRUNCATE = 500;

    /** 用于生成摘要的轻量模型 */
    private static final String COMPRESS_MODEL = "qwen-turbo";

    /** 异步摘要生成的超时时间（秒） */
    private static final int SUMMARY_TIMEOUT_SECONDS = 10;

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
        // 设为较高优先级（数字小先执行），保证压缩发生在日志打印等其他 Advisor 之前
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

    /**
     * 滑动窗口压缩核心逻辑：
     * <ol>
     *     <li>当消息数达到窗口阈值时，将最早的 COMPRESS_COUNT 条异步压缩为一条摘要</li>
     *     <li>压缩后的消息列表替换原请求中的 messages</li>
     *     <li>压缩后的消息列表通过 {@link RedisChatMemory#replace} 原子回写 Redis（存储级压缩）</li>
     * </ol>
     */
    private AdvisedRequest compressIfNeeded(AdvisedRequest request) {
        List<Message> messages = request.messages();
        if (messages == null || messages.size() < WINDOW_SIZE) {
            return request;
        }

        log.info("[记忆压缩] 触发滑动窗口压缩，当前消息数: {}，压缩前 {} 条为摘要",
                messages.size(), COMPRESS_COUNT);

        List<Message> earlyMessages = messages.subList(0, COMPRESS_COUNT);
        List<Message> remainMessages = messages.subList(COMPRESS_COUNT, messages.size());

        // 异步生成摘要，带超时控制
        String summary = summarizeAsync(earlyMessages);

        List<Message> compressed = new ArrayList<>(remainMessages.size() + 1);
        compressed.add(new SystemMessage("以下是之前对话的摘要（用于保持上下文连贯性）：\n" + summary));
        compressed.addAll(remainMessages);

        log.info("[记忆压缩] 压缩完成: {} 条 → {} 条（含 1 条摘要）",
                messages.size(), compressed.size());

        // 存储级压缩：将压缩后的消息列表原子回写 Redis
        writeBackToRedis(compressed);

        return AdvisedRequest.from(request).messages(compressed).build();
    }

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

    /**
     * 异步生成摘要，带超时控制。
     * <p>
     * 通过 {@link CompletableFuture} 在独立线程中调用轻量模型生成摘要，
     * 主线程等待最多 {@link #SUMMARY_TIMEOUT_SECONDS} 秒。
     * 超时或异常时降级为简单截断兜底，确保不阻塞主调用链过长时间。
     *
     * @param messages 需要被压缩的早期消息
     * @return 摘要文本
     */
    private String summarizeAsync(List<Message> messages) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> doSummarize(messages));
        try {
            String result = future.get(SUMMARY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result != null && !result.isBlank()) {
                return result;
            }
        } catch (TimeoutException e) {
            log.warn("[记忆压缩] 摘要生成超时（{}s），使用简单截断兜底", SUMMARY_TIMEOUT_SECONDS);
            future.cancel(true);
        } catch (Exception e) {
            log.warn("[记忆压缩] 异步摘要生成异常，使用简单截断兜底: {}", e.getMessage());
        }
        return buildFallbackSummary(messages);
    }

    /**
     * 使用轻量模型对早期消息生成摘要。
     */
    private String doSummarize(List<Message> messages) {
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
                
                要求：摘要控制在 200 字以内，使用中文，只输出摘要内容。
                
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
            log.warn("[记忆压缩] 摘要生成失败: {}", e.getMessage());
        }
        return null;
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
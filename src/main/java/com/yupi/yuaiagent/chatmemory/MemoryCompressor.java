package com.yupi.yuaiagent.chatmemory;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆压缩器
 * <p>
 * 在精华写入 Redis 之前，检查现有历史 + 新增精华是否超出 Token 预算。
 * 超预算时，将早期历史压缩为摘要后与新增精华一起原子回写 Redis。
 * <p>
 * 压缩只针对 Redis 中的持久化历史（即"用户问题 + 最终回答"精华），
 * 与 ReAct 循环的工作记忆（messageList）完全隔离，不会误伤当前推理链。
 */
@Slf4j
@Component
public class MemoryCompressor {

    /**
     * 历史记忆 Token 预算上限。
     * 推算：每轮精华（用户问题 + 最终回答）约 400-800 token，保留 10 轮 ≈ 8000。
     */
    private static final int MAX_MEMORY_TOKENS = 8000;

    private static final double TOKEN_PER_CHAR = 1.5;

    private static final double COMPRESS_BUFFER_RATIO = 0.3;

    private static final int SINGLE_MESSAGE_TRUNCATE = 500;

    private static final String COMPRESS_MODEL = "qwen-turbo";

    private final ChatClient summaryChatClient;
    private final RedisChatMemory redisChatMemory;

    public MemoryCompressor(ChatModel dashscopeChatModel, RedisChatMemory redisChatMemory) {
        this.summaryChatClient = ChatClient.builder(dashscopeChatModel).build();
        this.redisChatMemory = redisChatMemory;
    }

    /**
     * 写入精华前的压缩检查入口。
     * <p>
     * 流程：
     * <ol>
     *     <li>从 Redis 读取当前历史</li>
     *     <li>合并新增精华，估算总 token</li>
     *     <li>未超预算 → 直接 add 写入</li>
     *     <li>超预算 → 压缩早期历史为摘要 → replace 原子回写（含新增精华）</li>
     * </ol>
     */
    public void addWithCompaction(String conversationId, List<Message> newMessages) {
        List<Message> existingHistory = redisChatMemory.get(conversationId, 100);

        List<Message> merged = new ArrayList<>(existingHistory.size() + newMessages.size());
        merged.addAll(existingHistory);
        merged.addAll(newMessages);

        int totalTokens = estimateTokens(merged);
        if (totalTokens <= MAX_MEMORY_TOKENS) {
            redisChatMemory.add(conversationId, newMessages);
            return;
        }

        // 需要压缩：只对已有历史做压缩，保留新增精华完整
        int excess = totalTokens - MAX_MEMORY_TOKENS;
        int buffer = (int) (MAX_MEMORY_TOKENS * COMPRESS_BUFFER_RATIO);
        int cutTarget = excess + buffer;
        int cutIndex = findCutIndex(existingHistory, cutTarget);

        log.info("[记忆压缩] 触发压缩，历史 {} 条(≈{} token)，新增 {} 条，超出≈{}，压缩前 {} 条",
                existingHistory.size(), estimateTokens(existingHistory),
                newMessages.size(), excess, cutIndex);

        List<Message> earlyMessages = existingHistory.subList(0, cutIndex);
        List<Message> remainHistory = existingHistory.subList(cutIndex, existingHistory.size());

        String summary = summarize(earlyMessages);

        List<Message> compressed = new ArrayList<>();
        compressed.add(new SystemMessage("以下是之前对话的摘要（用于保持上下文连贯性）：\n" + summary));
        compressed.addAll(remainHistory);
        compressed.addAll(newMessages);

        log.info("[记忆压缩] 压缩完成: {} 条(≈{} token) → {} 条(≈{} token)",
                merged.size(), totalTokens, compressed.size(), estimateTokens(compressed));

        redisChatMemory.replace(conversationId, compressed);
    }

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

    private int findCutIndex(List<Message> messages, int excess) {
        int accumulated = 0;
        for (int i = 0; i < messages.size() - 1; i++) {
            String text = messages.get(i).getText();
            accumulated += (int) ((text != null ? text.length() : 0) * TOKEN_PER_CHAR);
            if (accumulated >= excess) {
                return i + 1;
            }
        }
        return Math.max(1, messages.size() - 1);
    }

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
                2. 助手给出了什么回答/结论
                3. 对话的关键上下文（以便后续对话能衔接）
                
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
package com.yupi.yuaiagent.agent;

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
 * 自适应会话记忆压缩器
 * <p>
 * 在 ReAct 循环中，messageList 会随着每轮 think/act 不断膨胀（User + Assistant + ToolResponse），
 * 当消息总量超过阈值时，自动将早期对话压缩为摘要，保留近期上下文原文。
 * <p>
 * 压缩策略（分三级）：
 * <ul>
 *     <li>无需压缩：消息数 ≤ {@link #NO_COMPRESS_THRESHOLD} 时不做处理</li>
 *     <li>温和压缩：消息数 ≤ {@link #AGGRESSIVE_THRESHOLD} 时，保留最近 {@link #RECENT_KEEP_COUNT} 条，早期压缩为摘要</li>
 *     <li>激进压缩：消息数 > {@link #AGGRESSIVE_THRESHOLD} 时，只保留最近 {@link #AGGRESSIVE_KEEP_COUNT} 条，其余全部压缩</li>
 * </ul>
 * <p>
 * 使用轻量模型（qwen-turbo）执行摘要，降低成本与延迟。
 */
@Component
@Slf4j
public class AdaptiveMemoryCompressor {

    /** 使用轻量模型做摘要压缩 */
    private static final String COMPRESS_MODEL = "qwen-turbo";

    /** 不压缩阈值：消息数 ≤ 此值时不触发压缩 */
    private static final int NO_COMPRESS_THRESHOLD = 8;

    /** 激进压缩阈值：消息数 > 此值时启用激进模式 */
    private static final int AGGRESSIVE_THRESHOLD = 16;

    /** 温和压缩时保留的最近消息条数 */
    private static final int RECENT_KEEP_COUNT = 6;

    /** 激进压缩时保留的最近消息条数 */
    private static final int AGGRESSIVE_KEEP_COUNT = 4;

    private final ChatClient chatClient;

    public AdaptiveMemoryCompressor(ChatModel dashscopeChatModel) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
    }

    /**
     * 对消息列表执行自适应压缩
     * <p>
     * 注意：此方法会返回一个新的消息列表（压缩后），不修改原列表。
     *
     * @param messages 当前完整的消息列表
     * @return 压缩后的消息列表（如不需要压缩则原样返回）
     */
    public List<Message> compressIfNeeded(List<Message> messages) {
        if (messages == null || messages.size() <= NO_COMPRESS_THRESHOLD) {
            return messages;
        }

        int keepCount;
        String level;
        if (messages.size() > AGGRESSIVE_THRESHOLD) {
            keepCount = AGGRESSIVE_KEEP_COUNT;
            level = "激进";
        } else {
            keepCount = RECENT_KEEP_COUNT;
            level = "温和";
        }

        log.info("[记忆压缩] 当前消息数: {}, 压缩级别: {}, 保留最近 {} 条",
                messages.size(), level, keepCount);

        // 分割：早期消息（待压缩）+ 近期消息（保留原文）
        int splitIndex = messages.size() - keepCount;
        List<Message> earlyMessages = messages.subList(0, splitIndex);
        List<Message> recentMessages = messages.subList(splitIndex, messages.size());

        // 将早期消息压缩为摘要
        String summary = summarize(earlyMessages);

        // 组装新的消息列表：摘要 + 近期原文
        List<Message> compressed = new ArrayList<>();
        compressed.add(new SystemMessage(
                "以下是之前对话的摘要（用于保持上下文连贯性）：\n" + summary));
        compressed.addAll(recentMessages);

        log.info("[记忆压缩] 压缩完成: {} 条消息 → {} 条（含1条摘要）",
                messages.size(), compressed.size());

        return compressed;
    }

    /**
     * 使用 LLM 对早期消息生成摘要
     *
     * @param messages 待摘要的消息列表
     * @return 摘要文本
     */
    private String summarize(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            String role = switch (msg.getMessageType()) {
                case USER -> "用户";
                case ASSISTANT -> "助手";
                case TOOL -> "工具返回";
                case SYSTEM -> "系统";
            };
            String text = msg.getText();
            // 对过长的单条消息做截断（工具返回可能很长）
            if (text != null && text.length() > 500) {
                text = text.substring(0, 500) + "...（已截断）";
            }
            sb.append(role).append("：").append(text).append("\n");
        }

        String summarizePrompt = String.format("""
                请将以下对话历史压缩为一段简洁的摘要，保留关键信息：
                1. 用户提出了什么问题/需求
                2. 助手做了哪些操作（调用了什么工具、得到了什么结果）
                3. 当前任务的进展状态
                
                要求：摘要控制在 200 字以内，使用中文，只输出摘要内容。
                
                === 对话历史 ===
                %s
                """, sb);

        try {
            DashScopeChatOptions options = DashScopeChatOptions.builder()
                    .withModel(COMPRESS_MODEL)
                    .withTemperature(0.0)
                    .build();

            String result = chatClient.prompt()
                    .user(summarizePrompt)
                    .options(options)
                    .call()
                    .content();

            if (result != null && !result.isBlank()) {
                log.info("[记忆压缩] 摘要生成成功，长度: {} 字", result.length());
                return result.trim();
            }
        } catch (Exception e) {
            log.warn("[记忆压缩] 摘要生成失败，使用简单截断: {}", e.getMessage());
        }

        // 兜底：LLM 摘要失败时，简单保留最后几条消息的文本
        return "（摘要生成失败，以下为对话关键片段）\n" +
                messages.stream()
                        .filter(m -> m.getText() != null)
                        .map(m -> m.getText().length() > 100
                                ? m.getText().substring(0, 100) + "..." : m.getText())
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("无历史记录");
    }
}
package com.yupi.yuaiagent.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.yuaiagent.agent.model.IntentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 意图分类器
 * <p>
 * 优化点：
 * 1. 规则前置过滤：命中关键词/正则时直接返回，避免调用 LLM
 * 2. 使用轻量模型 qwen-turbo 降低延迟与成本
 */
@Component
@Slf4j
public class IntentClassifier {

    /** 分类模型：使用更轻量的 qwen-turbo，降低延迟与成本 */
    private static final String CLASSIFY_MODEL = "qwen-turbo";

    /** CHAT 关键词（短句寒暄/感谢类） */
    private static final List<String> CHAT_KEYWORDS = Arrays.asList(
            "你好", "您好", "hi", "hello", "hey", "在吗", "在不在",
            "早上好", "中午好", "下午好", "晚上好", "晚安",
            "谢谢", "感谢", "多谢", "thanks", "thank you",
            "再见", "拜拜", "bye", "goodbye",
            "哈哈", "呵呵", "嗯嗯", "好的", "ok", "okay"
    );

    /** CHAT 正则模式（自我介绍 / 问身份类） */
    private static final Pattern CHAT_PATTERN = Pattern.compile(
            "^(你是谁|你叫什么|介绍一下你自己|你能做什么|你有什么功能).{0,10}[?？]?$"
    );

    private final ChatClient chatClient;

    public IntentClassifier(ChatModel dashscopeChatModel) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
    }

    /**
     * 对用户输入进行意图分类
     *
     * @param userPrompt 经过查询重写后的完整用户问题
     * @return 意图类型
     */
    public IntentType classify(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return IntentType.TASK;
        }

        // 1. 规则前置过滤：短文本 + 关键词/正则命中 -> 直接判 CHAT
        IntentType ruleHit = ruleBasedClassify(userPrompt);
        if (ruleHit != null) {
            log.info("意图分类（规则命中）：{} -> {}", userPrompt, ruleHit);
            return ruleHit;
        }

        // 2. 兜底：调用 LLM（使用轻量模型 qwen-turbo）
        return classifyByLlm(userPrompt);
    }

    /**
     * 规则前置过滤：针对典型闲聊场景做零成本短路
     *
     * @return 命中返回对应意图；未命中返回 null，交由 LLM 分类
     */
    private IntentType ruleBasedClassify(String userPrompt) {
        String trimmed = userPrompt.trim();
        // 短文本（<=15 字）才走关键词匹配，避免长文本误伤
        if (trimmed.length() <= 15) {
            String lower = trimmed.toLowerCase();
            for (String keyword : CHAT_KEYWORDS) {
                if (lower.equals(keyword) || lower.startsWith(keyword) || lower.endsWith(keyword)) {
                    return IntentType.CHAT;
                }
            }
        }
        if (CHAT_PATTERN.matcher(trimmed).matches()) {
            return IntentType.CHAT;
        }
        return null;
    }

    /**
     * 调用 LLM 进行 Zero-Shot 分类（指定轻量模型 qwen-turbo）
     */
    private IntentType classifyByLlm(String userPrompt) {
        String classifyPrompt = String.format("""
                你是一个意图分类器，请判断以下用户输入属于哪种意图类型。
                只返回以下枚举值之一，不要解释，不要输出任何其他内容：

                - CHAT：闲聊、问候、感谢、日常寒暄等非业务问题
                - KNOWLEDGE：询问南京大学信息管理学院相关的知识，如学院介绍、专业设置、师资力量、招生信息、科研成果、校园生活等
                - TASK：需要执行复杂操作的任务，如搜索网络信息、生成文件/PDF、下载资源、操作终端等
                - REJECT：涉及违规、隐私、政治敏感或完全超出服务范围的问题

                用户输入：%s
                """, userPrompt);

        try {
            // 通过 DashScopeChatOptions 单次覆盖模型，不影响全局配置
            DashScopeChatOptions options = DashScopeChatOptions.builder()
                    .withModel(CLASSIFY_MODEL)
                    .withTemperature(0.0)
                    .build();

            String result = chatClient.prompt()
                    .user(classifyPrompt)
                    .options(options)
                    .call()
                    .content();

            if (result != null) {
                result = result.trim().toUpperCase();
                // 处理 LLM 可能返回带引号或多余字符的情况
                result = result.replaceAll("[^A-Z_]", "");
                IntentType intentType = IntentType.valueOf(result);
                log.info("意图分类（LLM）：{} -> {}", userPrompt, intentType);
                return intentType;
            }
        } catch (Exception e) {
            log.warn("意图分类失败，默认使用 TASK 意图：{}", e.getMessage());
        }
        // 分类失败时默认走完整 ReAct 循环，保证功能不受影响
        return IntentType.TASK;
    }
}
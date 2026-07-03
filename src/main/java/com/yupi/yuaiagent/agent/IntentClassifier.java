package com.yupi.yuaiagent.agent;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.yuaiagent.agent.model.ClassifyResult;
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
 * 两阶段分类：
 * 1. 规则前置过滤：命中关键词/正则时直接返回（confidence=1.0），避免调用 LLM
 * 2. LLM Few-Shot 分类：返回 JSON 格式的意图 + 置信度 + 候选意图
 */
@Component
@Slf4j
public class IntentClassifier {

    private static final String CLASSIFY_MODEL = "qwen-turbo";

    private static final List<String> CHAT_KEYWORDS = Arrays.asList(
            "你好", "您好", "hi", "hello", "hey", "在吗", "在不在",
            "早上好", "中午好", "下午好", "晚上好", "晚安",
            "谢谢", "感谢", "多谢", "thanks", "thank you",
            "再见", "拜拜", "bye", "goodbye",
            "哈哈", "呵呵", "嗯嗯", "好的", "ok", "okay"
    );

    private static final Pattern CHAT_PATTERN = Pattern.compile(
            "^(你是谁|你叫什么|介绍一下你自己|你能做什么|你有什么功能).{0,10}[?？]?$"
    );

    private final ChatClient chatClient;

    public IntentClassifier(ChatModel dashscopeChatModel) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
    }

    /**
     * 对用户输入进行意图分类，返回意图 + 置信度 + 候选意图。
     *
     * @param userPrompt 经过查询重写后的完整用户问题
     * @return 分类结果（含置信度）
     */
    public ClassifyResult classify(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return new ClassifyResult(IntentType.TASK, 0.5, null);
        }

        // 1. 规则前置过滤：短文本 + 关键词/正则命中 -> 直接判定，confidence=1.0
        IntentType ruleHit = ruleBasedClassify(userPrompt);
        if (ruleHit != null) {
            log.info("意图分类（规则命中）：{} -> {}", userPrompt, ruleHit);
            return ClassifyResult.ofRule(ruleHit);
        }

        // 2. 兜底：调用 LLM，返回含置信度的结构化结果
        return classifyByLlm(userPrompt);
    }

    private IntentType ruleBasedClassify(String userPrompt) {
        String trimmed = userPrompt.trim();
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
     * 调用 LLM 进行 Few-Shot 分类，要求返回 JSON 格式的意图 + 置信度 + 候选意图。
     */
    private ClassifyResult classifyByLlm(String userPrompt) {
        String classifyPrompt = String.format("""
                你是一个意图分类器。请判断以下用户输入属于哪种意图类型，并给出你的置信度。
                请严格以 JSON 格式返回结果，不要输出任何其他内容：

                {"intent": "意图类型", "confidence": 置信度, "runnerUp": "第二候选意图或null"}

                意图类型说明：
                - CHAT：闲聊、问候、感谢、日常寒暄等非业务问题
                - KNOWLEDGE：询问南京大学信息管理学院相关的知识，如学院介绍、专业设置、师资力量、招生信息、科研成果、校园生活等
                - TASK：需要执行复杂操作的任务，如搜索网络信息、生成文件/PDF、下载资源、操作终端等
                - REJECT：涉及违规、隐私、政治敏感或完全超出服务范围的问题

                置信度说明：
                - 1.0 表示完全确定
                - 0.5 表示不太确定
                - runnerUp 是第二可能的意图类型，如果没有明显的第二选择则设为 null

                示例：
                用户输入：信管学院的研究生导师有哪些？
                {"intent": "KNOWLEDGE", "confidence": 0.95, "runnerUp": null}

                用户输入：帮我搜索一下最新的深度学习论文并生成总结PDF
                {"intent": "TASK", "confidence": 0.9, "runnerUp": "KNOWLEDGE"}

                用户输入：今天天气怎么样
                {"intent": "REJECT", "confidence": 0.85, "runnerUp": "CHAT"}

                用户输入：%s
                """, userPrompt);

        try {
            DashScopeChatOptions options = DashScopeChatOptions.builder()
                    .withModel(CLASSIFY_MODEL)
                    .withTemperature(0.0)
                    .build();

            String result = chatClient.prompt()
                    .user(classifyPrompt)
                    .options(options)
                    .call()
                    .content();

            ClassifyResult classifyResult = parseClassifyResponse(result);
            log.info("意图分类（LLM）：{} -> intent={}, confidence={}, runnerUp={}",
                    userPrompt, classifyResult.intent(), classifyResult.confidence(), classifyResult.runnerUp());
            return classifyResult;
        } catch (Exception e) {
            log.warn("意图分类 LLM 调用失败，降级为 TASK（confidence=0.5）：{}", e.getMessage());
            return new ClassifyResult(IntentType.TASK, 0.5, null);
        }
    }

    /**
     * 解析 LLM 返回的 JSON 分类结果，三层降级：
     * 1. JSON 解析成功 → 提取 intent + confidence + runnerUp
     * 2. JSON 解析失败 → 正则提取枚举名，confidence 降为 0.5
     * 3. 全部失败 → TASK, 0.5, null
     */
    private ClassifyResult parseClassifyResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ClassifyResult(IntentType.TASK, 0.5, null);
        }

        // 第一层：JSON 解析
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String json = raw.substring(start, end + 1);
                JSONObject obj = JSONUtil.parseObj(json);

                String intentStr = obj.getStr("intent", "TASK").trim().toUpperCase().replaceAll("[^A-Z_]", "");
                IntentType intent = IntentType.valueOf(intentStr);

                double confidence = obj.getDouble("confidence", 0.5);
                confidence = Math.max(0.0, Math.min(1.0, confidence));

                IntentType runnerUp = parseRunnerUp(obj.getStr("runnerUp"));
                if (runnerUp == intent) {
                    runnerUp = null;
                }

                return new ClassifyResult(intent, confidence, runnerUp);
            }
        } catch (Exception e) {
            log.debug("JSON 解析失败，尝试正则回退: {}", e.getMessage());
        }

        // 第二层：正则提取枚举名
        try {
            String cleaned = raw.trim().toUpperCase().replaceAll("[^A-Z_]", "");
            IntentType intent = IntentType.valueOf(cleaned);
            return new ClassifyResult(intent, 0.5, null);
        } catch (IllegalArgumentException ignored) {
        }

        // 第三层：全部失败
        return new ClassifyResult(IntentType.TASK, 0.5, null);
    }

    private IntentType parseRunnerUp(String runnerUpStr) {
        if (runnerUpStr == null || "null".equalsIgnoreCase(runnerUpStr.trim())) {
            return null;
        }
        try {
            return IntentType.valueOf(runnerUpStr.trim().toUpperCase().replaceAll("[^A-Z_]", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

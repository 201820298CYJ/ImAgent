package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.advisor.AdaptiveMemoryCompressorAdvisor;
import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.rag.QueryRewriter;
import com.yupi.yuaiagent.tools.KnowledgeBaseQueryTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * 南京大学信息管理学院智能助理 - 超级智能体
 * <p>
 * 集成了查询重写 + 意图分类 + ReAct 循环的完整 Agent。
 * <p>
 * Advisor 链路：
 * <ul>
 *     <li>{@link MessageChatMemoryAdvisor}：自动读写 Redis 会话记忆，请求前注入历史、响应后回写</li>
 *     <li>{@link AdaptiveMemoryCompressorAdvisor}：滑动窗口 + 摘要压缩，控制 LLM 入参 token</li>
 *     <li>{@link MyLoggerAdvisor}：调用链日志</li>
 * </ul>
 * 根据意图分类结果动态路由到不同策略：闲聊/知识库问答/复杂任务/礼貌拒绝。
 */
public class YuManus extends ToolCallAgent {

    public YuManus(ToolCallback[] allTools, ChatModel dashscopeChatModel, ChatMemory chatMemory,
                   QueryRewriter queryRewriter, IntentClassifier intentClassifier,
                   AdaptiveMemoryCompressorAdvisor memoryCompressorAdvisor,
                   KnowledgeBaseQueryTool knowledgeBaseQueryTool,
                   String conversationId) {
        super(allTools);
        this.setChatMemory(chatMemory);
        this.setQueryRewriter(queryRewriter);
        this.setIntentClassifier(intentClassifier);
        this.setKnowledgeBaseQueryTool(knowledgeBaseQueryTool);
        this.setName("imManus");

        String SYSTEM_PROMPT = """
                你是南京大学信息管理学院的智能AI助理，具备以下核心能力：
                1. 学院知识问答：通过知识库工具检索学院相关信息（学院介绍、专业设置、师资力量、招生信息等）
                2. 网络信息搜索：通过搜索工具获取最新资讯
                3. 文件操作：支持文件读写、PDF生成、资源下载等
                4. 终端操作：可执行系统命令完成自动化任务

                行为规范：
                - 使用专业、亲切的语言，体现学院"诚朴雄伟、励学敦行"的校训精神
                - 如信息来自知识库，据实回答；如知识库无相关内容，如实告知并建议通过官方渠道咨询
                - 对于复杂问题，可分步骤思考并组合使用多个工具
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);

        String NEXT_STEP_PROMPT = """
                根据用户需求，主动选择最合适的工具或工具组合来完成任务。
                对于复杂任务，可以将问题拆解并逐步使用不同工具解决。
                每次使用工具后，清楚地解释执行结果并建议下一步操作。
                如果任务已完成或无需进一步操作，请调用 terminate 工具结束交互。
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(10);

        // 初始化 AI 对话客户端，注册 Advisor 链：会话记忆 + 记忆压缩 + 日志
        // 顺序说明：MessageChatMemoryAdvisor 先注入历史，再由压缩 Advisor 控制窗口大小
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory)
                                .conversationId(conversationId)
                                .build(),
                        memoryCompressorAdvisor,
                        new MyLoggerAdvisor()
                )
                .build();
        this.setChatClient(chatClient);
    }
}
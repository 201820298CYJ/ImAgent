package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.advisor.ReadOnlyMemoryAdvisor;
import com.yupi.yuaiagent.chatmemory.MemoryCompressor;
import com.yupi.yuaiagent.chatmemory.RedisChatMemory;
import com.yupi.yuaiagent.rag.QueryRewriter;
import com.yupi.yuaiagent.tools.KnowledgeBaseQueryTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * 南京大学信息管理学院智能助理 - 超级智能体
 * <p>
 * 集成了查询重写 + 意图分类 + ReAct 循环的完整 Agent。
 * <p>
 * Advisor 链路：
 * <ul>
 *     <li>{@link ReadOnlyMemoryAdvisor}：只读注入 Redis 历史，不回写（回写由 BaseAgent 统一控制）</li>
 *     <li>{@link MyLoggerAdvisor}：调用链日志</li>
 * </ul>
 * 记忆压缩时机：在 BaseAgent.persistEssentialMemory 写入精华时，
 * 通过 {@link MemoryCompressor} 检查 Token 预算，超预算自动压缩早期历史。
 * 压缩只针对 Redis 中的持久化精华，完全不碰当前 ReAct 的 messageList。
 */
public class YuManus extends ToolCallAgent {

    public YuManus(ToolCallback[] allTools, ChatModel dashscopeChatModel,
                   RedisChatMemory redisChatMemory,
                   MemoryCompressor memoryCompressor,
                   QueryRewriter queryRewriter, IntentClassifier intentClassifier,
                   KnowledgeBaseQueryTool knowledgeBaseQueryTool,
                   String conversationId) {
        super(allTools);
        this.setChatMemory(redisChatMemory);
        this.setMemoryCompressor(memoryCompressor);
        this.setQueryRewriter(queryRewriter);
        this.setIntentClassifier(intentClassifier);
        this.setKnowledgeBaseQueryTool(knowledgeBaseQueryTool);
        this.setName("imManus");

        String SYSTEM_PROMPT = String.format("""
            当前日期：%s

            你是南京大学信息管理学院的专属智能AI助理，仅服务于与南京大学信息管理学院相关的咨询。

            核心能力：
            1. 学院知识问答：通过知识库工具检索学院相关信息（学院介绍、专业设置、师资力量、招生信息、科研成果等）
            2. 网络信息搜索：通过搜索工具获取与学院相关的最新资讯
            3. 网页内容抓取：通过网页抓取工具获取指定网页的详细内容

            边界约束：
            - 你只回答与南京大学信息管理学院相关的问题，包括但不限于：学院概况、专业介绍、师资队伍、招生就业、科研动态、学生活动、课程设置等
            - 如果用户的问题与南京大学信息管理学院无关，礼貌拒绝并说明你的服务范围，引导用户提出与学院相关的问题
            - 不回答涉及政治敏感、违法违规、或与学院业务完全无关的问题

            行为规范：
            - 使用专业、亲切的语言，体现学院"诚朴雄伟、励学敦行"的校训精神
            - 如信息来自知识库，据实回答；如知识库无相关内容，如实告知并建议通过官方渠道咨询
            - 对于复杂问题，可分步骤思考并组合使用多个工具
            """, java.time.LocalDate.now());

        this.setSystemPrompt(SYSTEM_PROMPT);

        String NEXT_STEP_PROMPT = """
                根据用户需求，主动选择最合适的工具或工具组合来完成任务。
                对于复杂任务，可以将问题拆解并逐步使用不同工具解决。
                每次使用工具后，清楚地解释执行结果并建议下一步操作。
                如果任务已完成或无需进一步操作，请调用 terminate 工具结束交互。
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(10);

        // 只读记忆注入 Advisor：请求前注入 Redis 历史，不在响应后回写
        ReadOnlyMemoryAdvisor readOnlyMemoryAdvisor =
                new ReadOnlyMemoryAdvisor(redisChatMemory, conversationId);

        // 初始化 AI 对话客户端，注册 Advisor 链：只读记忆注入 + 日志
        // 压缩逻辑已下沉到 persistEssentialMemory，通过 MemoryCompressor 在写入时判断
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(readOnlyMemoryAdvisor, new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}
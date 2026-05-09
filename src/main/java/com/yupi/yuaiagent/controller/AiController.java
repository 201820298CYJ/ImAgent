package com.yupi.yuaiagent.controller;

import com.yupi.yuaiagent.agent.AdaptiveMemoryCompressor;
import com.yupi.yuaiagent.agent.IntentClassifier;
import com.yupi.yuaiagent.agent.YuManus;
import com.yupi.yuaiagent.chatmemory.FileBasedChatMemory;
import com.yupi.yuaiagent.rag.QueryRewriter;
import com.yupi.yuaiagent.tools.KnowledgeBaseQueryTool;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 对外 HTTP 入口
 * 底层链路：查询重写 → 意图分类路由 → ReAct 循环（按意图动态调整）
 * <ul>
 *     <li>CHAT：直接友好回复，1 步终止</li>
 *     <li>KNOWLEDGE：路由到 queryKnowledgeBase 工具（Hybrid Search + Rerank）</li>
 *     <li>TASK：完整 ReAct 循环，全工具可用（文件/网络/终端/PDF/MCP）</li>
 *     <li>REJECT：礼貌拒绝并引导至官方渠道</li>
 * </ul>
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    @Resource
    private QueryRewriter queryRewriter;

    @Resource
    private IntentClassifier intentClassifier;

    @Resource
    private AdaptiveMemoryCompressor memoryCompressor;

    @Resource
    private KnowledgeBaseQueryTool knowledgeBaseQueryTool;

    /** 使用文件存储记忆，确保重启后会话上下文仍可用 */
    private final ChatMemory chatMemory = new FileBasedChatMemory(System.getProperty("user.dir") + "/tmp/manus-memory");

    /**
     * 流式调用 Manus 超级智能体（唯一对外 AI 入口）
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message, String chatId) {
        YuManus yuManus = new YuManus(allTools, dashscopeChatModel, chatMemory, queryRewriter, intentClassifier, memoryCompressor, knowledgeBaseQueryTool);
        yuManus.setConversationId(chatId);
        return yuManus.runStream(message);
    }
}
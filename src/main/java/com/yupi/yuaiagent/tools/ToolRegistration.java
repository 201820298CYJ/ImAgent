package com.yupi.yuaiagent.tools;

import com.yupi.yuaiagent.rag.DashScopeRerankService;
import com.yupi.yuaiagent.rag.HybridSearchService;
import jakarta.annotation.Resource;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 集中的工具注册类
 *
 * MCP Server（yu-agent-tools-mcp-server）提供的工具：
 * - WebSearchTool
 * - WebScrapingTool
 *
 * 本地工具：
 * - TerminateTool（Agent 内部流程控制）
 * - KnowledgeBaseQueryTool（依赖 RAG 基础设施）
 */
@Configuration
public class ToolRegistration {

    @Autowired(required = false)
    private ToolCallbackProvider toolCallbackProvider;

    @Resource
    private HybridSearchService hybridSearchService;

    @Resource
    private DashScopeRerankService dashScopeRerankService;

    @Bean
    public KnowledgeBaseQueryTool knowledgeBaseQueryTool() {
        return new KnowledgeBaseQueryTool(hybridSearchService, dashScopeRerankService);
    }

    @Bean
    public ToolCallback[] allTools() {
        TerminateTool terminateTool = new TerminateTool();
        KnowledgeBaseQueryTool knowledgeBaseQueryTool = knowledgeBaseQueryTool();

        // 1. 本地工具
        ToolCallback[] localTools = ToolCallbacks.from(
                terminateTool,
                knowledgeBaseQueryTool
        );

        // 2. MCP 工具（WebSearch、WebScraping）
        List<ToolCallback> allToolList = new ArrayList<>(Arrays.asList(localTools));
        if (toolCallbackProvider != null) {
            FunctionCallback[] mcpTools = toolCallbackProvider.getToolCallbacks();
            if (mcpTools != null) {
                for (FunctionCallback mcpTool : mcpTools) {
                    if (mcpTool instanceof ToolCallback) {
                        allToolList.add((ToolCallback) mcpTool);
                    }
                }
            }
        }

        return allToolList.toArray(new ToolCallback[0]);
    }
}

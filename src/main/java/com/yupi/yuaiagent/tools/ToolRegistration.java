package com.yupi.yuaiagent.tools;

import com.yupi.yuaiagent.rag.DashScopeRerankService;
import com.yupi.yuaiagent.rag.HybridSearchService;
import jakarta.annotation.Resource;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 集中的工具注册类
 */
@Configuration
public class ToolRegistration {

    @Value("${search-api.api-key}")
    private String searchApiKey;

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
        FileOperationTool fileOperationTool = new FileOperationTool();
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool();
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        TerminateTool terminateTool = new TerminateTool();
        // 复用 Bean 实例
        KnowledgeBaseQueryTool knowledgeBaseQueryTool = knowledgeBaseQueryTool();

        // 1. 获取本地自定义工具
        ToolCallback[] localTools = ToolCallbacks.from(
                fileOperationTool,
                webSearchTool,
                webScrapingTool,
                resourceDownloadTool,
                terminalOperationTool,
                pdfGenerationTool,
                terminateTool,
                knowledgeBaseQueryTool
        );

        // 2. 获取 MCP 工具（如果有）
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



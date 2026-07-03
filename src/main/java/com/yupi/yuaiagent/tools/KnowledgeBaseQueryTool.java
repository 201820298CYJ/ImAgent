package com.yupi.yuaiagent.tools;

import com.yupi.yuaiagent.harness.TraceContext;
import com.yupi.yuaiagent.harness.TraceCollector;
import com.yupi.yuaiagent.harness.model.AgentRunTrace;
import com.yupi.yuaiagent.rag.DashScopeRerankService;
import com.yupi.yuaiagent.rag.HybridSearchService;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库查询工具
 * 将完整的 RAG 检索链路（Hybrid Search + Rerank）封装为 ReAct Agent 可调用的工具，
 * 使 Agent 在 Think 阶段可以自主决策何时查询知识库。
 *
 * 注意：查询重写已在 BaseAgent.preProcess 中完成，此处接收的 query 已是重写后的完整语句，
 * 因此直接调用 HybridSearchService 而非 RagService，避免重复重写。
 */
public class KnowledgeBaseQueryTool {

    private static final int VECTOR_TOP_K = 15;
    private static final int BM25_TOP_K = 15;
    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final int FUSION_TOP_N = 15;
    private static final int RERANK_TOP_K = 5;

    private final HybridSearchService hybridSearchService;
    private final DashScopeRerankService dashScopeRerankService;

    public KnowledgeBaseQueryTool(HybridSearchService hybridSearchService,
                                  DashScopeRerankService dashScopeRerankService) {
        this.hybridSearchService = hybridSearchService;
        this.dashScopeRerankService = dashScopeRerankService;
    }

    /**
     * 查询南京大学信息管理学院知识库
     *
     * @param query 查询关键词或问题
     * @return 检索到的知识库文档内容
     */
    @Tool(description = "查询南京大学信息管理学院本地知识库，可获取学院介绍、专业设置、师资力量、招生信息、科研成果、校园生活等学院相关内容。当用户问题涉及学院相关知识时，应优先调用此工具。")
    public String queryKnowledgeBase(
            @ToolParam(description = "查询关键词或完整问题，如：信息管理学院有哪些专业") String query) {

        // 1. 混合检索：向量语义召回 + BM25 关键词召回 + RRF 融合
        List<Document> fusedDocuments = hybridSearchService.hybridSearch(
                query, VECTOR_TOP_K, BM25_TOP_K, SIMILARITY_THRESHOLD, FUSION_TOP_N);

        // 上报检索结果到追踪收集器
        TraceCollector tc = TraceContext.get();
        if (tc != null) {
            for (Document doc : fusedDocuments) {
                double rrfScore = doc.getMetadata().containsKey("rrf_score")
                        ? ((Number) doc.getMetadata().get("rrf_score")).doubleValue() : 0.0;
                tc.addRetrievalEntry(new AgentRunTrace.RetrievalEntry(
                        doc.getId(),
                        doc.getText().substring(0, Math.min(200, doc.getText().length())),
                        rrfScore,
                        "rrf"
                ));
            }
        }

        if (fusedDocuments.isEmpty()) {
            return "知识库中未找到与该问题相关的内容，建议使用网络搜索工具获取更多信息。";
        }

        // 2. Rerank 精排：交叉编码器二次精排
        List<Document> rerankedDocuments = dashScopeRerankService.rerank(query, fusedDocuments, RERANK_TOP_K);

        // 上报 rerank 结果到追踪收集器
        if (tc != null) {
            for (Document doc : rerankedDocuments) {
                double rerankScore = doc.getMetadata().containsKey("rerank_score")
                        ? ((Number) doc.getMetadata().get("rerank_score")).doubleValue() : 0.0;
                tc.addRerankEntry(new AgentRunTrace.RetrievalEntry(
                        doc.getId(),
                        doc.getText().substring(0, Math.min(200, doc.getText().length())),
                        rerankScore,
                        "rerank"
                ));
            }
        }

        if (rerankedDocuments.isEmpty()) {
            return "知识库中未找到与该问题相关的内容，建议使用网络搜索工具获取更多信息。";
        }

        // 3. 拼接检索到的文档内容返回给 Agent
        return rerankedDocuments.stream()
                .map(doc -> {
                    String header = (String) doc.getMetadata().getOrDefault("header_context", "");
                    return header.isEmpty() ? doc.getText() : String.format("【%s】\n%s", header, doc.getText());
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
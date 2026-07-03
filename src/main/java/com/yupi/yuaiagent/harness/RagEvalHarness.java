package com.yupi.yuaiagent.harness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.harness.model.RagEvalResult;
import com.yupi.yuaiagent.harness.model.RagEvalResult.DocResult;
import com.yupi.yuaiagent.harness.model.RagEvalResult.QueryResult;
import com.yupi.yuaiagent.harness.model.RagEvalTestCase;
import com.yupi.yuaiagent.rag.DashScopeRerankService;
import com.yupi.yuaiagent.rag.HybridSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 检索质量评估引擎
 * <p>
 * 加载 JSON 测试集，执行完整 RAG 管线（HybridSearch + Rerank），
 * 计算 HitRate@5、MRR@5、NDCG@5 指标并输出评估报告。
 */
@Component
@Slf4j
public class RagEvalHarness {

    private static final int EVAL_TOP_K = 5;
    private static final int VECTOR_TOP_K = 15;
    private static final int BM25_TOP_K = 15;
    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final int FUSION_TOP_N = 15;

    private final HybridSearchService hybridSearchService;
    private final DashScopeRerankService dashScopeRerankService;
    private final ObjectMapper objectMapper;

    public RagEvalHarness(HybridSearchService hybridSearchService,
                          DashScopeRerankService dashScopeRerankService,
                          ObjectMapper objectMapper) {
        this.hybridSearchService = hybridSearchService;
        this.dashScopeRerankService = dashScopeRerankService;
        this.objectMapper = objectMapper;
    }

    public RagEvalResult evaluate() throws Exception {
        List<RagEvalTestCase> testCases = loadDataset();
        List<QueryResult> details = new ArrayList<>();

        int hitCount = 0;
        double mrrSum = 0.0;
        double ndcgSum = 0.0;

        for (RagEvalTestCase testCase : testCases) {
            log.info("[RAG评估] 正在评估: {} - {}", testCase.id(), testCase.question());

            // 1. HybridSearch
            List<Document> fusedDocs = hybridSearchService.hybridSearch(
                    testCase.question(), VECTOR_TOP_K, BM25_TOP_K, SIMILARITY_THRESHOLD, FUSION_TOP_N);

            // 2. Rerank
            List<Document> rerankedDocs = dashScopeRerankService.rerank(
                    testCase.question(), fusedDocs, EVAL_TOP_K);

            // 3. 判定相关性（关键词命中即为 relevant）
            List<DocResult> docResults = new ArrayList<>();
            int firstHitRank = 0;
            double dcg = 0.0;
            int relevantCount = 0;

            for (int rank = 0; rank < rerankedDocs.size(); rank++) {
                Document doc = rerankedDocs.get(rank);
                boolean relevant = isRelevant(doc, testCase.expectedKeywords());
                double score = doc.getMetadata().containsKey("rerank_score")
                        ? ((Number) doc.getMetadata().get("rerank_score")).doubleValue() : 0.0;

                docResults.add(new DocResult(
                        doc.getId(),
                        doc.getText().substring(0, Math.min(100, doc.getText().length())),
                        score,
                        relevant
                ));

                if (relevant) {
                    relevantCount++;
                    if (firstHitRank == 0) {
                        firstHitRank = rank + 1;
                    }
                    dcg += 1.0 / (Math.log(rank + 2) / Math.log(2));
                }
            }

            boolean hit = firstHitRank > 0;
            if (hit) hitCount++;

            double rr = firstHitRank > 0 ? 1.0 / firstHitRank : 0.0;
            mrrSum += rr;

            // NDCG: 理想 DCG = 所有相关文档排在最前面
            double idcg = 0.0;
            for (int i = 0; i < relevantCount; i++) {
                idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
            double ndcg = idcg > 0 ? dcg / idcg : 0.0;
            ndcgSum += ndcg;

            details.add(new QueryResult(testCase.id(), testCase.question(), hit, firstHitRank, dcg, docResults));

            log.info("[RAG评估] {} - hit={}, firstHitRank={}, RR={}, NDCG={}",
                    testCase.id(), hit, firstHitRank,
                    String.format("%.4f", rr), String.format("%.4f", ndcg));
        }

        int total = testCases.size();
        double hitRate = (double) hitCount / total;
        double mrr = mrrSum / total;
        double avgNdcg = ndcgSum / total;

        log.info("[RAG评估] 最终指标 - HitRate@{}: {}, MRR@{}: {}, NDCG@{}: {}",
                EVAL_TOP_K, String.format("%.4f", hitRate),
                EVAL_TOP_K, String.format("%.4f", mrr),
                EVAL_TOP_K, String.format("%.4f", avgNdcg));

        return new RagEvalResult(Instant.now(), total, hitRate, mrr, avgNdcg, details);
    }

    private boolean isRelevant(Document doc, List<String> expectedKeywords) {
        String text = doc.getText();
        String header = (String) doc.getMetadata().getOrDefault("header_context", "");
        String combined = text + " " + header;
        return expectedKeywords.stream().anyMatch(combined::contains);
    }

    private List<RagEvalTestCase> loadDataset() throws Exception {
        InputStream is = getClass().getClassLoader()
                .getResourceAsStream("harness/rag-eval-dataset.json");
        if (is == null) {
            throw new IllegalStateException("找不到评估数据集: harness/rag-eval-dataset.json");
        }
        return objectMapper.readValue(is, new TypeReference<>() {});
    }
}

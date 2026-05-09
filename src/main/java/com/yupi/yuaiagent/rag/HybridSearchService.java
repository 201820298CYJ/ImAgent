package com.yupi.yuaiagent.rag;

import com.yupi.yuaiagent.rag.es.EsDocumentService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索服务
 * 并行执行 pgvector 向量检索 + Elasticsearch BM25 关键词检索
 * 使用 RRF（Reciprocal Rank Fusion）算法融合两路结果
 */
@Service
@Slf4j
public class HybridSearchService {

    /**
     * RRF 融合参数 k，标准值为 60
     * 用于平滑排名差异，防止排名靠前的结果权重过大
     */
    private static final int RRF_K = 60;

    @Resource
    private VectorStore pgVectorVectorStore;

    @Resource
    private EsDocumentService esDocumentService;

    /**
     * 执行混合检索
     *
     * @param queryText          查询文本
     * @param vectorTopK         向量检索返回数量
     * @param bm25TopK           BM25 检索返回数量
     * @param similarityThreshold 向量检索相似度阈值
     * @param fusionTopN         RRF 融合后返回数量
     * @return 融合排序后的文档列表
     */
    public List<Document> hybridSearch(String queryText,
                                       int vectorTopK,
                                       int bm25TopK,
                                       double similarityThreshold,
                                       int fusionTopN) {
        // 1. 向量检索（语义召回）
        List<Document> vectorResults = pgVectorVectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(queryText)
                        .topK(vectorTopK)
                        .similarityThreshold(similarityThreshold)
                        .build()
        );
        log.info("向量检索召回 {} 条文档", vectorResults.size());

        // 2. BM25 关键词检索（精确召回）
        List<Document> bm25Results = esDocumentService.searchByBM25(queryText, bm25TopK);
        log.info("BM25 检索召回 {} 条文档", bm25Results.size());

        // 3. RRF 融合
        List<Document> fusedResults = rrfFusion(vectorResults, bm25Results, fusionTopN);
        log.info("RRF 融合后返回 {} 条文档", fusedResults.size());

        return fusedResults;
    }

    /**
     * RRF（Reciprocal Rank Fusion）融合算法
     * 公式：RRF_score(d) = Σ 1 / (k + rank_i(d))
     * 其中 k 为平滑参数（默认 60），rank_i(d) 为文档 d 在第 i 路检索中的排名（从 1 开始）
     *
     * @param vectorResults 向量检索结果（已按相似度排序）
     * @param bm25Results   BM25 检索结果（已按 BM25 分数排序）
     * @param topN          返回数量
     * @return 融合后的文档列表
     */
    private List<Document> rrfFusion(List<Document> vectorResults,
                                     List<Document> bm25Results,
                                     int topN) {
        // 用 Map 记录每个文档的 RRF 总分，key 为文档 ID
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        // 同时记录文档对象引用，用于最终返回
        Map<String, Document> documentMap = new LinkedHashMap<>();

        // 向量检索结果计分
        for (int rank = 0; rank < vectorResults.size(); rank++) {
            Document doc = vectorResults.get(rank);
            double score = 1.0 / (RRF_K + rank + 1); // rank 从 1 开始
            rrfScores.merge(doc.getId(), score, Double::sum);
            documentMap.putIfAbsent(doc.getId(), doc);
        }

        // BM25 检索结果计分
        for (int rank = 0; rank < bm25Results.size(); rank++) {
            Document doc = bm25Results.get(rank);
            double score = 1.0 / (RRF_K + rank + 1);
            rrfScores.merge(doc.getId(), score, Double::sum);
            documentMap.putIfAbsent(doc.getId(), doc);
        }

        // 按 RRF 总分降序排序，取 Top N
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topN)
                .map(entry -> {
                    Document doc = documentMap.get(entry.getKey());
                    // 将 RRF 分数写入 metadata，方便调试观察
                    doc.getMetadata().put("rrf_score", entry.getValue());
                    return doc;
                })
                .collect(Collectors.toList());
    }
}
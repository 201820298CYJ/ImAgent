package com.yupi.yuaiagent.harness.model;

import java.time.Instant;
import java.util.List;

/**
 * RAG 评估报告
 */
public record RagEvalResult(
        Instant evaluatedAt,
        int totalQueries,
        double hitRateAt5,
        double mrrAt5,
        double ndcgAt5,
        List<QueryResult> details
) {
    /** 单条查询的评估结果 */
    public record QueryResult(
            String id,
            String question,
            boolean hit,
            int firstHitRank,
            double dcg,
            List<DocResult> retrievedDocs
    ) {}

    /** 单条检索到的文档 */
    public record DocResult(
            String documentId,
            String snippet,
            double score,
            boolean relevant
    ) {}
}

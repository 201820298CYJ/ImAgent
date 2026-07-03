package com.yupi.yuaiagent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.harness.model.RagEvalResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 检索质量回归测试
 * <p>
 * 运行前提：需要 PostgreSQL (pgvector) 和 Elasticsearch 在线，且已完成文档索引。
 */
@SpringBootTest
@Slf4j
class RagEvalHarnessTest {

    @Resource
    private RagEvalHarness ragEvalHarness;

    @Resource
    private ObjectMapper objectMapper;

    private static final double MIN_HIT_RATE = 0.8;
    private static final double MIN_MRR = 0.6;

    @Test
    void evaluateRagQuality() throws Exception {
        RagEvalResult result = ragEvalHarness.evaluate();

        String report = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        log.info("[RAG评估测试] 完整报告:\n{}", report);

        log.info("[RAG评估测试] HitRate@5 = {}, 阈值 = {}", result.hitRateAt5(), MIN_HIT_RATE);
        log.info("[RAG评估测试] MRR@5    = {}, 阈值 = {}", result.mrrAt5(), MIN_MRR);
        log.info("[RAG评估测试] NDCG@5   = {}", result.ndcgAt5());

        assertTrue(result.hitRateAt5() >= MIN_HIT_RATE,
                String.format("HitRate@5 = %.4f < 阈值 %.4f", result.hitRateAt5(), MIN_HIT_RATE));
        assertTrue(result.mrrAt5() >= MIN_MRR,
                String.format("MRR@5 = %.4f < 阈值 %.4f", result.mrrAt5(), MIN_MRR));
    }
}

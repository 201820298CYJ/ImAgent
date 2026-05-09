package com.yupi.yuaiagent.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@Slf4j
class RagRecallEvaluationTest {

    @Resource(name = "loveAppVectorStore")
    private VectorStore vectorStore;

    @TestConfiguration
    static class TestConfig {
        // Mock MyKeywordEnricher to avoid slow LLM calls during test context startup
        @Bean
        @Primary
        public MyKeywordEnricher myKeywordEnricher() {
            return new MyKeywordEnricher() {
                @Override
                public List<Document> enrichDocuments(List<Document> documents) {
                    log.info("Mock MyKeywordEnricher: Skipping enrichment for {} documents", documents.size());
                    return documents;
                }
            };
        }

        // Mock pgVectorVectorStore to satisfy LoveApp dependency
        @Bean(name = "pgVectorVectorStore")
        public VectorStore pgVectorVectorStore() {
            return Mockito.mock(VectorStore.class);
        }
    }

    @Test
    void evaluateRecall() {
        // 定义测试用例：Query -> 期望包含的关键词或内容片段
        Map<String, String> testCases = Map.of(
            "南京大学信息管理学院官方网站是什么？", "im.nju.edu.cn",
            "谁是2022年的院长？", "裴雷",
            "图书馆学专业学什么", "培养理论基础厚",
            "哪一年加入国际iSchools组织", "2011年",
            "学院拥有多少教职工", "80余人"
        );

        AtomicInteger successCount = new AtomicInteger(0);
        // 使用数组来绕过 Lambda 的 final 限制 (或者使用 AtomicReference<Double>)
        // 这里为了简单，我们把 forEach 改为增强 for 循环，或者使用外部变量
        double[] totalPrecisionWrapper = {0.0};
        int total = testCases.size();

        testCases.forEach((query, expected) -> {
            log.info("Testing Query: {}", query);
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(3).build()
            );
            
            long relevantCount = results.stream()
                    .filter(doc -> doc.getText().contains(expected))
                    .count();
            
            if (relevantCount > 0) {
                successCount.incrementAndGet();
                log.info("✅ Hit! Found {} relevant docs.", relevantCount);
            } else {
                log.info("❌ Miss. Expected content '{}' not found.", expected);
                results.forEach(doc -> log.info("Snippet: {}", doc.getText().substring(0, Math.min(50, doc.getText().length())).replace("\n", " ")));
            }
            
            // 累加精度（当前查询的 Precision = 相关文档数 / K）
            totalPrecisionWrapper[0] += (double) relevantCount / results.size();
            
            log.info("--------------------------------------------------");
        });

        // 1. Hit Rate (命中率/召回率)：有多少个问题找到了至少一个答案
        // 在 RAG 中，只要 TopK 里有一个对的，通常就认为“召回成功”，所以常作为 Recall 的替代指标
        double hitRate = (double) successCount.get() / total * 100;
        
        // 2. Average Precision (平均查准率)：每次搜索出来的 3 条结果里，平均有百分之多少是对的
        double avgPrecision = totalPrecisionWrapper[0] / total * 100;

        log.info("Evaluation Result:");
        log.info(" -> Hit Rate (Recall@3): {}%  (Found answer for {}/{} queries)", String.format("%.2f", hitRate), successCount.get(), total);
        log.info(" -> Mean Precision@3:    {}%  (Avg relevance density)", String.format("%.2f", avgPrecision));
    }
}

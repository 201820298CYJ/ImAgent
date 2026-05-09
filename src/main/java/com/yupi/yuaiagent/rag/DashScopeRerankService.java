package com.yupi.yuaiagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * DashScope Rerank 重排序服务
 * 通过 HTTP API 调用 DashScope 文本排序模型（gte-rerank-v2 / qwen3-rerank）
 * 基于交叉编码器（Cross-Encoder）计算 query 与每个文档的精确相关性分数
 *
 * API 文档：https://help.aliyun.com/zh/model-studio/text-rerank-api
 */
@Service
@Slf4j
public class DashScopeRerankService {

    private static final String RERANK_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    @Value("${dashscope.rerank.api-key:${spring.ai.dashscope.api-key}}")
    private String apiKey;

    @Value("${dashscope.rerank.model:gte-rerank-v2}")
    private String modelName;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 对候选文档进行重排序
     *
     * @param query     用户查询
     * @param documents 候选文档列表（来自 HybridSearch RRF 融合后的结果）
     * @param topK      精排后返回的文档数量
     * @return 重排序后的文档列表
     */
    public List<Document> rerank(String query, List<Document> documents, int topK) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            // 1. 提取文档正文作为候选文本
            List<String> textList = documents.stream()
                    .map(Document::getText)
                    .collect(Collectors.toList());

            // 2. 构建 HTTP 请求体
            Map<String, Object> requestBody = buildRequestBody(query, textList, topK, documents.size());

            // 3. 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<String> httpEntity = new HttpEntity<>(
                    objectMapper.writeValueAsString(requestBody), headers);

            // 4. 调用 DashScope Rerank API
            ResponseEntity<String> response = restTemplate.exchange(
                    RERANK_API_URL, HttpMethod.POST, httpEntity, String.class);

            // 5. 解析响应，按 rerank 分数重新排序
            return parseRerankResponse(response.getBody(), documents);

        } catch (Exception e) {
            log.error("Rerank 调用失败，降级返回原始排序", e);
            // 降级策略：Rerank 失败时返回原始排序的 topK
            return documents.stream().limit(topK).collect(Collectors.toList());
        }
    }

    /**
     * 构建 Rerank API 请求体
     * 格式参见：https://help.aliyun.com/zh/model-studio/text-rerank-api
     */
    private Map<String, Object> buildRequestBody(String query, List<String> documents,
                                                  int topN, int totalDocs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);

        // input 结构
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("documents", documents);
        body.put("input", input);

        // parameters 结构
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("top_n", Math.min(topN, totalDocs));
        parameters.put("return_documents", false);
        body.put("parameters", parameters);

        return body;
    }

    /**
     * 解析 Rerank API 返回结果
     * 响应格式：
     * {
     *   "output": {
     *     "results": [
     *       { "index": 0, "relevance_score": 0.9334 },
     *       { "index": 2, "relevance_score": 0.3410 }
     *     ]
     *   },
     *   "usage": { "total_tokens": 79 },
     *   "request_id": "..."
     * }
     */
    private List<Document> parseRerankResponse(String responseBody,
                                                List<Document> originalDocuments) {
        List<Document> rerankedDocuments = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 检查是否有错误
            if (root.has("code") && !root.get("code").asText().isEmpty()) {
                log.error("Rerank API 返回错误: code={}, message={}",
                        root.get("code").asText(), root.get("message").asText());
                return originalDocuments;
            }

            JsonNode results = root.path("output").path("results");
            if (results.isMissingNode() || !results.isArray()) {
                log.warn("Rerank API 响应中未找到 results 数组");
                return originalDocuments;
            }

            for (JsonNode item : results) {
                int index = item.get("index").asInt();
                double relevanceScore = item.get("relevance_score").asDouble();

                if (index >= 0 && index < originalDocuments.size()) {
                    Document doc = originalDocuments.get(index);
                    // 将 rerank 分数写入 metadata
                    doc.getMetadata().put("rerank_score", relevanceScore);
                    rerankedDocuments.add(doc);
                }
            }

            log.info("Rerank 完成，返回 {} 条文档，最高分: {}, 最低分: {}",
                    rerankedDocuments.size(),
                    rerankedDocuments.isEmpty() ? "N/A" :
                            rerankedDocuments.get(0).getMetadata().get("rerank_score"),
                    rerankedDocuments.isEmpty() ? "N/A" :
                            rerankedDocuments.get(rerankedDocuments.size() - 1)
                                    .getMetadata().get("rerank_score"));

        } catch (Exception e) {
            log.error("Rerank 结果解析失败", e);
            return originalDocuments;
        }

        return rerankedDocuments;
    }
}

package com.yupi.yuaiagent.rag.es;

import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ES 文档服务
 * 负责文档写入 ES 索引和 BM25 关键词检索
 */
@Service
@Slf4j
public class EsDocumentService {

    private final KnowledgeDocumentRepository repository;
    private final ElasticsearchOperations elasticsearchOperations;

    public EsDocumentService(KnowledgeDocumentRepository repository,
                             ElasticsearchOperations elasticsearchOperations) {
        this.repository = repository;
        this.elasticsearchOperations = elasticsearchOperations;
    }

    /**
     * 清空 ES 索引中的所有文档
     */
    public void deleteAll() {
        repository.deleteAll();
        log.info("已清空 ES 索引");
    }

    /**
     * 批量写入文档到 ES
     *
     * @param documents Spring AI Document 列表
     */
    public void indexDocuments(List<Document> documents) {
        List<KnowledgeDocument> esDocs = documents.stream()
                .map(this::toKnowledgeDocument)
                .toList();
        repository.saveAll(esDocs);
        log.info("成功写入 {} 条文档到 ES 索引", esDocs.size());
    }

    /**
     * BM25 多字段检索
     * 对 content（正文）、keywords（关键词）、headerContext（标题）三个字段进行加权检索
     *
     * @param queryText 查询文本
     * @param topK      返回数量
     * @return 检索结果转换为 Spring AI Document
     */
    public List<Document> searchByBM25(String queryText, int topK) {
        // 多字段加权检索：content^1.0, keywords^2.0, headerContext^1.5
        Query multiMatchQuery = new Query.Builder()
                .multiMatch(new MultiMatchQuery.Builder()
                        .query(queryText)
                        .fields("content^1.0", "keywords^2.0", "headerContext^1.5")
                        .build())
                .build();

        NativeQuery searchQuery = NativeQuery.builder()
                .withQuery(multiMatchQuery)
                .withMaxResults(topK)
                .build();

        SearchHits<KnowledgeDocument> searchHits =
                elasticsearchOperations.search(searchQuery, KnowledgeDocument.class);

        return searchHits.getSearchHits().stream()
                .map(this::toSpringAiDocument)
                .toList();
    }

    /**
     * Spring AI Document → ES KnowledgeDocument
     */
    private KnowledgeDocument toKnowledgeDocument(Document doc) {
        return KnowledgeDocument.builder()
                .id(doc.getId())
                .content(doc.getText())
                .keywords((String) doc.getMetadata().getOrDefault("excerpt_keywords", ""))
                .headerContext((String) doc.getMetadata().getOrDefault("header_context", ""))
                .filename((String) doc.getMetadata().getOrDefault("filename", ""))
                .build();
    }

    /**
     * ES SearchHit → Spring AI Document
     */
    private Document toSpringAiDocument(SearchHit<KnowledgeDocument> hit) {
        KnowledgeDocument esDoc = hit.getContent();
        Map<String, Object> metadata = Map.of(
                "source", "elasticsearch",
                "score", hit.getScore(),
                "filename", esDoc.getFilename() != null ? esDoc.getFilename() : "",
                "header_context", esDoc.getHeaderContext() != null ? esDoc.getHeaderContext() : ""
        );
        return new Document(esDoc.getId(), esDoc.getContent(), metadata);
    }
}
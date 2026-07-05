package com.yupi.yuaiagent.rag;

import com.yupi.yuaiagent.rag.es.EsDocumentService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文档索引服务
 * 负责文档的加载、切分，并双写到 pgvector 和 Elasticsearch
 */
@Service
@Slf4j
public class DocumentIndexService {

    @Resource
    private DocumentLoader documentLoader;

    @Resource
    private MarkdownStructureSplitter markdownStructureSplitter;

    @Resource
    private VectorStore pgVectorVectorStore;

    @Resource
    private EsDocumentService esDocumentService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 应用启动时自动构建索引
     * 加载 Markdown 文档 → 结构化切分 → 双写入库
     */
    @PostConstruct
    public void buildIndex() {
        log.info("====== 开始构建知识库索引 ======");

        // 0. 清空旧索引
        jdbcTemplate.execute("TRUNCATE TABLE vector_store");
        log.info("已清空 pgvector 向量表");
        esDocumentService.deleteAll();

        // 1. 加载 Markdown 文档
        List<Document> rawDocuments = documentLoader.loadMarkdowns();
        log.info("加载到 {} 篇原始文档", rawDocuments.size());

        // 2. 结构化切分（按标题层级切分 + TokenTextSplitter 二级切分）
        List<Document> splitDocuments = markdownStructureSplitter.apply(rawDocuments);
        log.info("切分为 {} 个文档片段", splitDocuments.size());

        // 3. 双写入库
        // 3.1 写入 pgvector（向量检索）—— DashScope Embedding 单次最多 25 条，需要分批
        int embeddingBatchSize = 25;
        for (int i = 0; i < splitDocuments.size(); i += embeddingBatchSize) {
            List<Document> batch = splitDocuments.subList(
                    i, Math.min(i + embeddingBatchSize, splitDocuments.size()));
            pgVectorVectorStore.add(batch);
            log.info("pgvector 写入进度：{}/{}",
                    Math.min(i + embeddingBatchSize, splitDocuments.size()), splitDocuments.size());
        }
        log.info("成功写入 pgvector 向量数据库");

        // 3.2 写入 Elasticsearch（BM25 关键词检索）
        esDocumentService.indexDocuments(splitDocuments);
        log.info("成功写入 Elasticsearch 索引");

        log.info("====== 知识库索引构建完成 ======");
    }
}
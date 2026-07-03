package com.yupi.yuaiagent.rag;

import com.yupi.yuaiagent.rag.es.EsDocumentService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文档索引服务
 * 负责文档的加载、切分、增强，并双写到 pgvector 和 Elasticsearch
 */
@Service
@Slf4j
public class DocumentIndexService {

    @Resource
    private DocumentLoader documentLoader;

    @Resource
    private MarkdownStructureSplitter markdownStructureSplitter;

    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    @Resource
    private VectorStore pgVectorVectorStore;

    @Resource
    private EsDocumentService esDocumentService;

    /**
     * 应用启动时自动构建索引
     * 加载 Markdown 文档 → 结构化切分 → AI 关键词增强 → 双写入库
     */
    @PostConstruct
    public void buildIndex() {
        log.info("====== 开始构建知识库索引 ======");

        // 1. 加载 Markdown 文档
        List<Document> rawDocuments = documentLoader.loadMarkdowns();
        log.info("加载到 {} 篇原始文档", rawDocuments.size());

        // 2. 结构化切分（按标题层级切分 + TokenTextSplitter 二级切分）
        List<Document> splitDocuments = markdownStructureSplitter.apply(rawDocuments);
        log.info("切分为 {} 个文档片段", splitDocuments.size());

        // 3. AI 关键词增强（为每个片段提取关键词并拼接到正文）
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(splitDocuments);
        log.info("关键词增强完成，共 {} 个文档片段", enrichedDocuments.size());

        // 4. 双写入库
        // 4.1 写入 pgvector（向量检索）—— DashScope Embedding 单次最多 25 条，需要分批
        int embeddingBatchSize = 25;
        for (int i = 0; i < enrichedDocuments.size(); i += embeddingBatchSize) {
            List<Document> batch = enrichedDocuments.subList(
                    i, Math.min(i + embeddingBatchSize, enrichedDocuments.size()));
            pgVectorVectorStore.add(batch);
            log.info("pgvector 写入进度：{}/{}",
                    Math.min(i + embeddingBatchSize, enrichedDocuments.size()), enrichedDocuments.size());
        }
        log.info("成功写入 pgvector 向量数据库");

        // 4.2 写入 Elasticsearch（BM25 关键词检索）
        esDocumentService.indexDocuments(enrichedDocuments);
        log.info("成功写入 Elasticsearch 索引");

        log.info("====== 知识库索引构建完成 ======");
    }
}
package com.yupi.yuaiagent.rag.es;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * ES 知识库文档 Repository
 */
@Repository
public interface KnowledgeDocumentRepository extends ElasticsearchRepository<KnowledgeDocument, String> {

}
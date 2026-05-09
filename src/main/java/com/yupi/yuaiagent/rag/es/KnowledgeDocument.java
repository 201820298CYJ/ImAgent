package com.yupi.yuaiagent.rag.es;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

/**
 * ES 知识库文档实体
 * 使用 IK 分词器进行中文 BM25 检索
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "knowledge_document")
@Setting(settingPath = "/es/knowledge-document-settings.json")
public class KnowledgeDocument {

    @Id
    private String id;

    /**
     * 文档正文内容（IK 中文分词）
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    /**
     * AI 提取的关键词（IK 分词，提升精确匹配权重）
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String keywords;

    /**
     * 文档标题/标题上下文
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String headerContext;

    /**
     * 来源文件名
     */
    @Field(type = FieldType.Keyword)
    private String filename;
}
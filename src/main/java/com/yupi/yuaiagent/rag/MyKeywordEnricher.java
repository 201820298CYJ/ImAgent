package com.yupi.yuaiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 AI 的文档元信息增强器（为文档补充元信息）
 */
@Component
public class MyKeywordEnricher {

    @Resource
    private ChatModel dashscopeChatModel;

    public List<Document> enrichDocuments(List<Document> documents) {
        KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(dashscopeChatModel, 5);
        List<Document> enrichedDocuments = keywordMetadataEnricher.apply(documents);

        // 显式将关键词拼接到正文前面，确保 Embedding 包含它们
        return enrichedDocuments.stream().map(doc -> {
            String keywords = (String) doc.getMetadata().get("excerpt_keywords");
            if (keywords != null && !keywords.isEmpty()) {
                // 拼接格式：【关键词：xxx, yyy】\n 原文
                String newContent = String.format("【关键词：%s】\n%s", keywords, doc.getText());
                // 返回新文档（ID 最好保持不变或重新生成，这里简化处理）
                return new Document(doc.getId(), newContent, doc.getMetadata());
            }
            return doc;
        }).toList();
    }
}

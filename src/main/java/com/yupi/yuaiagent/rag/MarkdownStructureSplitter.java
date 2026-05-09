package com.yupi.yuaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.Map;
import java.util.HashMap;

/**
 * 自定义 Markdown 结构化切分器
 * 根据 Markdown 标题层级进行切分，并将标题作为上下文注入到元数据中
 */
@Component
public class MarkdownStructureSplitter extends TextSplitter {

    // 匹配 # 标题的正则（匹配行首的 #）
    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*)$", Pattern.MULTILINE);

    // 内部使用 TokenTextSplitter 进行长文本的二级切分
    // 默认每个 chunk 800 tokens，重叠 100 tokens
    private final TokenTextSplitter tokenSplitter = new TokenTextSplitter(500, 100, 5, 10000, true);

    @Override
    protected List<String> splitText(String text) {
        // 由于我们需要操作 Metadata，逻辑主要在 apply 中实现
        // 这里返回空列表或原文本均可，实际上不会被我们的 apply 调用
        return List.of(text);
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> splitDocuments = new ArrayList<>();

        for (Document doc : documents) {
            String content = doc.getText();
            Matcher matcher = HEADER_PATTERN.matcher(content);
            List<Integer> splitIndices = new ArrayList<>();
            List<String> headers = new ArrayList<>();

            while (matcher.find()) {
                splitIndices.add(matcher.start());
                headers.add(matcher.group(2)); // 提取标题文本
            }

            // 如果没有标题，直接作为整块（可能需要二级切分）
            if (splitIndices.isEmpty()) {
                addChunk(splitDocuments, content, "无标题", doc.getMetadata());
                continue;
            }

            // 开始切分
            int lastIndex = 0;
            String currentHeader = "无标题"; // 默认上下文

            for (int i = 0; i < splitIndices.size(); i++) {
                int splitIndex = splitIndices.get(i);
                
                // 处理上一段内容（从上一个标题位置到当前标题位置之前）
                if (splitIndex > lastIndex) {
                    String chunkText = content.substring(lastIndex, splitIndex).trim();
                    addChunk(splitDocuments, chunkText, currentHeader, doc.getMetadata());
                }
                
                // 更新当前标题（作为下一段的上下文）
                currentHeader = headers.get(i);
                // 更新位置到当前标题开始处
                lastIndex = splitIndex;
            }

            // 处理最后一段（最后一个标题到文档结束）
            if (lastIndex < content.length()) {
                String chunkText = content.substring(lastIndex).trim();
                addChunk(splitDocuments, chunkText, currentHeader, doc.getMetadata());
            }
        }
        return splitDocuments;
    }

    private void addChunk(List<Document> splitDocuments, String text, String header, Map<String, Object> metadata) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        // 构造当前块的 Metadata（继承原 Metadata + 添加 header_context）
        Map<String, Object> newMetadata = new HashMap<>(metadata);
        newMetadata.put("header_context", header);

        // 使用 TokenTextSplitter 进行二级切分
        // 注意：TokenTextSplitter.apply 接受 List<Document>，所以我们先构造一个 Document
        Document tempDoc = new Document(text, newMetadata);
        List<Document> subChunks = tokenSplitter.apply(List.of(tempDoc));
        
        splitDocuments.addAll(subChunks);
    }
}

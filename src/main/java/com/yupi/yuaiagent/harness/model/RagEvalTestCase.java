package com.yupi.yuaiagent.harness.model;

import java.util.List;

/**
 * RAG 评估数据集中的单条测试用例
 */
public record RagEvalTestCase(
        String id,
        String question,
        List<String> expectedKeywords,
        String expectedDocSection
) {}

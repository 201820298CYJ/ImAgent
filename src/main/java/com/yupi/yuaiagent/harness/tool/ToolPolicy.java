package com.yupi.yuaiagent.harness.tool;

import java.util.Map;

/**
 * 工具级差异化韧性策略。
 * <p>
 * 按工具 IO 特征设置差异化超时阈值，避免"一刀切"配置造成的误杀或放过：
 * <ul>
 *     <li>{@code queryKnowledgeBase} - 5s：PG + ES + Rerank 三段链路，正常 &lt;2s，5s 足够容忍抖动</li>
 *     <li>{@code searchWeb}          - 8s：外部搜索 API 走公网，需要更长容忍</li>
 *     <li>{@code scrapeWebPage}      - 10s：抓取真实网页，DOM 解析 + 大响应体传输，最慢</li>
 *     <li>{@code doTerminate}        - 1s：Agent 内部信号，纯本地逻辑</li>
 * </ul>
 * 未配置的工具使用默认策略。
 */
public record ToolPolicy(long timeoutMs) {

    /** 默认策略：5s 超时 */
    private static final ToolPolicy DEFAULT = new ToolPolicy(5_000L);

    /** 按工具名注册的策略表 */
    private static final Map<String, ToolPolicy> POLICIES = Map.of(
            "queryKnowledgeBase", new ToolPolicy(5_000L),
            "searchWeb",          new ToolPolicy(8_000L),
            "scrapeWebPage",      new ToolPolicy(10_000L),
            "doTerminate",        new ToolPolicy(1_000L)
    );

    /**
     * 根据工具名查询策略。未注册的工具返回默认策略。
     */
    public static ToolPolicy of(String toolName) {
        if (toolName == null) {
            return DEFAULT;
        }
        return POLICIES.getOrDefault(toolName, DEFAULT);
    }
}

package com.yupi.yuaiagent.agent.model;

/**
 * 意图分类结果：意图类型 + 置信度 + 候选意图（用于低置信度时的澄清提示）。
 *
 * @param intent     最高置信度的意图
 * @param confidence 置信度 [0.0, 1.0]
 * @param runnerUp   第二候选意图（可为 null，仅 LLM 路径提供）
 */
public record ClassifyResult(IntentType intent, double confidence, IntentType runnerUp) {

    /**
     * 规则命中时使用的快捷构造 —— confidence 固定为 1.0，无候选
     */
    public static ClassifyResult ofRule(IntentType intent) {
        return new ClassifyResult(intent, 1.0, null);
    }
}

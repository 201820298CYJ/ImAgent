package com.yupi.yuaiagent.agent.model;

/**
 * 用户意图类型枚举
 * 用于在 ReAct 循环前对用户输入进行意图分类，动态调整 Agent 行为策略
 */
public enum IntentType {

    /**
     * 闲聊/问候，直接 LLM 回复，减少 ReAct 步数
     */
    CHAT,

    /**
     * 知识库问答，优先引导 Agent 调用知识库工具
     */
    KNOWLEDGE,

    /**
     * 复杂任务，需要完整 ReAct 循环，全工具可用
     */
    TASK,

    /**
     * 不相关/违规问题，礼貌拒绝
     */
    REJECT
}
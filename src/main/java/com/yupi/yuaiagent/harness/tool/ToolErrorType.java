package com.yupi.yuaiagent.harness.tool;

/**
 * 工具执行错误分类。
 * <p>
 * 每种错误类型对应一种"回喂 LLM 的建议话术"，
 * 使 LLM 能在下一轮 think() 中根据错误类型自主决定纠偏策略：
 * 换参数、换工具、还是放弃后基于上下文作答。
 */
public enum ToolErrorType {

    /** 工具执行成功 */
    SUCCESS,

    /** 超过策略配置的超时阈值 */
    TIMEOUT,

    /** 参数错误（空参数、非法格式等），LLM 应重新调用并修正参数 */
    INVALID_ARG,

    /** 工具内部执行异常（下游服务不可用、IO 失败等），LLM 应换工具或基于上下文作答 */
    EXECUTION_ERROR
}

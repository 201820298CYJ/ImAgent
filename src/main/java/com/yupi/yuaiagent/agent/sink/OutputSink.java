package com.yupi.yuaiagent.agent.sink;

/**
 * Agent 输出适配器
 * <p>
 * 抽象 Agent 的输出通道，使核心执行流程（{@code execute}/各意图 handler）
 * 不必关心是同步返回还是 SSE 流式推送。
 * <ul>
 *     <li>{@link com.yupi.yuaiagent.agent.sink.BufferedOutputSink}：缓冲式，最终 join 返回（用于普通调用）</li>
 *     <li>{@link com.yupi.yuaiagent.agent.sink.SseOutputSink}：流式，逐段推送到 SseEmitter</li>
 * </ul>
 */
public interface OutputSink {

    /**
     * 发送一段输出。
     * 同步实现下表示追加到缓冲；流式实现下表示立即推送给客户端。
     */
    void send(String chunk);

    /**
     * 标记输出完成（成功结束）。
     */
    void complete();

    /**
     * 标记输出异常结束。
     *
     * @param errorMessage 错误说明（建议为业务可读文本）
     */
    void completeWithError(String errorMessage);
}
package com.yupi.yuaiagent.agent.sink;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 缓冲式输出 Sink：用于同步返回模式。
 * <p>
 * {@code send} 把每段输出存入内部 list；
 * 调用 {@link #toAggregatedString()} 可一次性获取拼接后的最终结果。
 * <p>
 * 状态语义：
 * <ul>
 *     <li>{@link #isCompleted()} = true：已完成（成功或异常都会置位）</li>
 *     <li>{@link #getErrorMessage()} != null：异常完成</li>
 * </ul>
 */
public class BufferedOutputSink implements OutputSink {

    private final List<String> chunks = new ArrayList<>();

    @Getter
    private boolean completed = false;

    @Getter
    private String errorMessage = null;

    @Override
    public void send(String chunk) {
        if (chunk != null) {
            chunks.add(chunk);
        }
    }

    @Override
    public void complete() {
        this.completed = true;
    }

    @Override
    public void completeWithError(String errorMessage) {
        this.errorMessage = errorMessage;
        this.completed = true;
    }

    /**
     * 获取最终聚合输出。
     * <p>异常优先：若有错误则返回错误消息，否则返回所有 chunk 的换行拼接。
     */
    public String toAggregatedString() {
        if (errorMessage != null) {
            return errorMessage;
        }
        return String.join("\n", chunks);
    }

    public List<String> getChunks() {
        return List.copyOf(chunks);
    }
}
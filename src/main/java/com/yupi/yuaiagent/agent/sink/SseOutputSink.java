package com.yupi.yuaiagent.agent.sink;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * SSE 流式输出 Sink：把每段输出立即推送到客户端。
 * <p>
 * 异常处理：
 * <ul>
 *     <li>{@code send} 失败仅记录日志，不中断核心流程（避免 IOException 污染业务代码）</li>
 *     <li>{@link #completeWithError} 优先尝试推送错误消息再 complete；推送失败则 completeWithError</li>
 * </ul>
 */
@Slf4j
public class SseOutputSink implements OutputSink {

    private final SseEmitter sseEmitter;

    public SseOutputSink(SseEmitter sseEmitter) {
        this.sseEmitter = sseEmitter;
    }

    @Override
    public void send(String chunk) {
        if (chunk == null) {
            return;
        }
        try {
            sseEmitter.send(chunk);
        } catch (IOException e) {
            // 客户端可能已断开，记录日志即可，外层会感知 emitter 状态
            log.warn("SSE 推送失败：{}", e.getMessage());
        }
    }

    @Override
    public void complete() {
        try {
            sseEmitter.complete();
        } catch (Exception e) {
            log.warn("SSE 完成时异常：{}", e.getMessage());
        }
    }

    @Override
    public void completeWithError(String errorMessage) {
        try {
            if (errorMessage != null && !errorMessage.isEmpty()) {
                sseEmitter.send(errorMessage);
            }
            sseEmitter.complete();
        } catch (IOException e) {
            // 推送失败时，回退到底层 completeWithError 关闭连接
            sseEmitter.completeWithError(e);
        }
    }

    /** 暴露原始 emitter 供外层注册 onTimeout/onCompletion 等回调 */
    public SseEmitter getEmitter() {
        return sseEmitter;
    }
}
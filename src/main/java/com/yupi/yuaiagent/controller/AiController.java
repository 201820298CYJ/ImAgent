package com.yupi.yuaiagent.controller;

import com.yupi.yuaiagent.harness.AgentHarness;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 对外 HTTP 入口
 * <p>
 * 所有请求委托给 {@link AgentHarness} 编排层，实现关注点分离：
 * Controller 只负责 HTTP 协议，Harness 负责 Agent 生命周期和追踪。
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private AgentHarness agentHarness;

    /**
     * 流式调用 Manus 超级智能体（唯一对外 AI 入口）
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message, String chatId) {
        return agentHarness.runStream(message, chatId);
    }
}
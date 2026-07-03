package com.yupi.yuaiagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.yuaiagent.agent.model.AgentState;
import com.yupi.yuaiagent.agent.sink.OutputSink;
import com.yupi.yuaiagent.harness.TraceContext;
import com.yupi.yuaiagent.harness.TraceCollector;
import com.yupi.yuaiagent.harness.model.AgentRunTrace;
import com.yupi.yuaiagent.harness.tool.ToolResilientExecutor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    // 可用的工具
    private final ToolCallback[] availableTools;

    // 保存工具调用信息的响应结果（要调用那些工具）
    private ChatResponse toolCallChatResponse;

    // 工具调用管理者
    private final ToolCallingManager toolCallingManager;

    // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
    private final ChatOptions chatOptions;

    /**
     * 工具韧性执行层（由 AgentHarness 注入）。
     * 若未注入则退化为原有 ToolCallingManager 串行执行路径，保证向后兼容。
     */
    private ToolResilientExecutor toolResilientExecutor;

    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
        this.chatOptions = DashScopeChatOptions.builder()
                .withProxyToolCalls(true)
                .build();
    }

    /**
     * 处理当前状态并决定下一步行动。
     * <p>
     * 走流式接口：把每个非空文本 chunk 通过 {@link OutputSink} 立即推给客户端，
     * 同时收集完整的 {@link ChatResponse} 用于后续 act() 阶段解析工具调用。
     *
     * @return 是否需要执行行动
     */
    @Override
    public boolean think() {
        // 1、校验提示词，拼接用户提示词
        if (StrUtil.isNotBlank(getNextStepPrompt())) {
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMessageList().add(userMessage);
        }
        // 2、调用 AI 大模型（流式），获取工具调用结果
        List<Message> messageList = getMessageList();
        Prompt prompt = new Prompt(messageList, this.chatOptions);
        OutputSink sink = getCurrentSink();
        try {
            ChatResponse chatResponse = streamThinkAndCollect(prompt, sink);
            // 记录响应，用于等下 Act
            this.toolCallChatResponse = chatResponse;
            // 3、解析工具调用结果，获取要调用的工具
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            String result = assistantMessage.getText();
            log.info(getName() + "的思考：" + result);
            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);
            // 如果不需要调用工具，返回 false
            if (toolCallList.isEmpty()) {
                // 只有不调用工具时，才需要手动记录助手消息
                getMessageList().add(assistantMessage);
                return false;
            } else {
                // 需要调用工具时，无需记录助手消息，因为调用工具时会自动记录
                if (sink != null && !toolCallList.isEmpty()) {
                    sink.send("\n> 调用工具：" + toolCallList.stream()
                            .map(AssistantMessage.ToolCall::name)
                            .collect(Collectors.joining("、")));
                }
                return true;
            }
        } catch (Exception e) {
            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
            getMessageList().add(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
            if (sink != null) {
                sink.send("处理时遇到了错误：" + e.getMessage());
            }
            return false;
        }
    }

    /**
     * 阻塞式订阅 stream()，边收 chunk 边推 sink；最后一个 ChatResponse 汇总了所有工具调用与完整文本。
     * <p>
     * DashScope 的 stream 每个中间响应仅带增量文本，最后一个响应带完整的 ToolCall 列表，
     * 因此这里用 last-one-wins 保留最终响应，累积文本用于兜底日志。
     */
    private ChatResponse streamThinkAndCollect(Prompt prompt, OutputSink sink) {
        StringBuilder textBuffer = new StringBuilder();
        ChatResponse[] lastRef = new ChatResponse[1];
        getChatClient().prompt(prompt)
                .system(getSystemPrompt())
                .tools(availableTools)
                .stream()
                .chatResponse()
                .toStream()
                .forEach(resp -> {
                    lastRef[0] = resp;
                    if (resp == null || resp.getResult() == null) {
                        return;
                    }
                    AssistantMessage delta = resp.getResult().getOutput();
                    if (delta == null) {
                        return;
                    }
                    String text = delta.getText();
                    if (text == null || text.isEmpty()) {
                        return;
                    }
                    textBuffer.append(text);
                    if (sink != null) {
                        sink.send(text);
                    }
                });
        ChatResponse last = lastRef[0];
        if (last == null) {
            throw new IllegalStateException("LLM 未返回任何响应");
        }
        // 若最后一条响应的 AssistantMessage 没带完整文本（stream 增量下常见），
        // 用累积文本重建一个新的 AssistantMessage，保留 toolCalls / metadata。
        AssistantMessage originOutput = last.getResult().getOutput();
        String originText = originOutput.getText();
        if ((originText == null || originText.isEmpty()) && textBuffer.length() > 0) {
            AssistantMessage merged = new AssistantMessage(
                    textBuffer.toString(),
                    originOutput.getMetadata(),
                    originOutput.getToolCalls(),
                    originOutput.getMedia()
            );
            return ChatResponse.builder()
                    .from(last)
                    .generations(List.of(new Generation(merged, last.getResult().getMetadata())))
                    .build();
        }
        return last;
    }

    /**
     * 执行工具调用并处理结果。
     * <p>
     * 若注入了 {@link ToolResilientExecutor}，走"虚拟线程并行 + 超时 + 异常回喂"路径；
     * 否则退化为 {@link ToolCallingManager} 串行路径（向后兼容）。
     */
    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具需要调用";
        }
        List<AssistantMessage.ToolCall> toolCallList = toolCallChatResponse.getResult().getOutput().getToolCalls();

        // 路径 A：韧性执行层（虚拟线程并行 + 超时 + 异常回喂 LLM）
        if (toolResilientExecutor != null) {
            return actWithResilience(toolCallList);
        }
        // 路径 B：原有 ToolCallingManager 串行路径（回退兼容）
        return actWithLegacyManager(toolCallList);
    }

    /**
     * 韧性执行路径：虚拟线程并行调度，异常统一转 LLM 反馈。
     * <p>
     * 关键差异（与 legacy 路径对比）：
     * <ul>
     *     <li>多工具并行：耗时从 Σ 降到 max</li>
     *     <li>工具级差异化超时：KB 5s / searchWeb 8s / scrapeWebPage 10s</li>
     *     <li>异常不中断 ReAct 循环：失败工具转为"原因+建议"中文反馈塞回消息上下文，
     *         LLM 在下一轮 think() 中自主决定换参数/换工具/放弃</li>
     * </ul>
     */
    private String actWithResilience(List<AssistantMessage.ToolCall> toolCallList) {
        long actStart = System.currentTimeMillis();

        // 1. 手动把 Assistant 消息加入上下文（原来由 ToolCallingManager 自动做）
        getMessageList().add(toolCallChatResponse.getResult().getOutput());

        // 2. 并行调度（内部包含超时 + 异常结构化反馈，不会抛异常）
        List<ToolResponseMessage.ToolResponse> responses = toolResilientExecutor.executeAll(toolCallList, availableTools);

        // 3. 手动构建 ToolResponseMessage 塞回消息上下文（无论成功失败都塞）
        ToolResponseMessage toolResponseMessage = new ToolResponseMessage(new ArrayList<>(responses));
        getMessageList().add(toolResponseMessage);

        long actDuration = System.currentTimeMillis() - actStart;

        // 4. 上报每个工具调用到 Trace（每个工具用真实结果，不再平均分摊耗时）
        TraceCollector tc = TraceContext.get();
        if (tc != null) {
            for (int i = 0; i < toolCallList.size(); i++) {
                AssistantMessage.ToolCall toolCall = toolCallList.get(i);
                String responseData = i < responses.size() ? responses.get(i).responseData() : "";
                tc.addToolCall(new AgentRunTrace.ToolCallEntry(
                        toolCall.name(),
                        toolCall.arguments(),
                        responseData != null && responseData.length() > 500
                                ? responseData.substring(0, 500) : responseData,
                        actDuration
                ));
            }
        }

        // 5. 判断是否调用了终止工具
        boolean terminateToolCalled = responses.stream()
                .anyMatch(r -> r.name().equals("doTerminate"));
        if (terminateToolCalled) {
            setState(AgentState.FINISHED);
        }

        String results = responses.stream()
                .map(r -> "工具 " + r.name() + " 返回的结果：" + r.responseData())
                .collect(Collectors.joining("\n"));
        log.info(results);
        return results;
    }

    /**
     * 原有的 ToolCallingManager 串行路径。保留作为回退，未注入韧性执行器时使用。
     */
    private String actWithLegacyManager(List<AssistantMessage.ToolCall> toolCallList) {
        long actStart = System.currentTimeMillis();
        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        setMessageList(toolExecutionResult.conversationHistory());
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil
                .getLast(toolExecutionResult.conversationHistory());

        TraceCollector tc = TraceContext.get();
        if (tc != null) {
            long actDuration = System.currentTimeMillis() - actStart;
            List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses();
            for (int i = 0; i < toolCallList.size(); i++) {
                AssistantMessage.ToolCall toolCall = toolCallList.get(i);
                String responseData = (i < responses.size()) ? responses.get(i).responseData() : "";
                tc.addToolCall(new AgentRunTrace.ToolCallEntry(
                        toolCall.name(),
                        toolCall.arguments(),
                        responseData != null && responseData.length() > 500
                                ? responseData.substring(0, 500) : responseData,
                        actDuration / Math.max(1, toolCallList.size())
                ));
            }
        }

        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));
        if (terminateToolCalled) {
            setState(AgentState.FINISHED);
        }
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 返回的结果：" + response.responseData())
                .collect(Collectors.joining("\n"));
        log.info(results);
        return results;
    }
}

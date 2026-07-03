package com.yupi.yuaiagent.harness.tool;

/**
 * 工具异常 → LLM 可理解的中文反馈格式化。
 * <p>
 * 输出的每条反馈都遵循 "原因 + 建议" 两段式结构，
 * 使 LLM 在下一轮 think() 中能根据 "建议" 部分自主决定：
 * 换参数、换工具、还是放弃后基于上下文作答——即"自我纠偏"。
 * <p>
 * 关键设计理念：<b>错误消息也是 Prompt</b>。工具反馈不应是 Java 异常栈，
 * 而应是 LLM 可读的自然语言指令。
 */
public final class ToolFailureFormatter {

    private ToolFailureFormatter() {}

    /**
     * 根据错误类型格式化为结构化中文反馈。
     */
    public static String format(String toolName, ToolErrorType type, Throwable ex, ToolPolicy policy) {
        return switch (type) {
            case TIMEOUT -> String.format(
                    "工具 %s 执行超时（超过 %dms）。原因：调用未在预期时间内完成，可能是下游服务响应慢或参数过于复杂。建议：请尝试简化查询关键词后重新调用；若仍超时，请改用其他工具或基于对话上下文和已有信息作答，并向用户说明该工具暂时不可用。",
                    toolName, policy.timeoutMs());

            case INVALID_ARG -> String.format(
                    "工具 %s 参数错误。原因：%s。建议：请检查该工具的参数格式后重新调用。例如 queryKnowledgeBase 需要传入具体的检索关键词（如\"信息管理专业培养目标\"），searchWeb 需要传入完整搜索问题，scrapeWebPage 需要传入合法 URL。",
                    toolName, safeMessage(ex));

            case EXECUTION_ERROR -> String.format(
                    "工具 %s 执行失败。原因：%s。建议：请优先使用其他工具补全信息（例如知识库失败时改用 searchWeb，反之亦然）；若所有工具均不可用，请基于对话上下文和常识作答，并如实向用户说明该工具当前异常。",
                    toolName, safeMessage(ex));

            case SUCCESS -> "";
        };
    }

    /**
     * 安全提取异常消息：null 时返回 "未知错误"，避免拼接出 "原因：null" 干扰 LLM。
     */
    private static String safeMessage(Throwable ex) {
        if (ex == null) {
            return "未知错误";
        }
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        // 截断过长的异常消息，避免污染 LLM 上下文
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }
}

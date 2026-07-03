package com.yupi.yuaiagent.harness;

/**
 * ThreadLocal 持有当前请求的 TraceCollector。
 * <p>
 * 让深层组件（KnowledgeBaseQueryTool、ToolCallAgent）无需传参即可上报追踪数据。
 * 由 BaseAgent.execute() 在执行线程中 set/clear。
 */
public final class TraceContext {

    private static final ThreadLocal<TraceCollector> HOLDER = new ThreadLocal<>();

    private TraceContext() {}

    public static void set(TraceCollector collector) {
        HOLDER.set(collector);
    }

    public static TraceCollector get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}

package com.yupi.yuaiagent.harness;

import com.yupi.yuaiagent.harness.model.AgentRunTrace;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 有界内存 Trace 存储（最近 100 条，FIFO 淘汰）
 */
@Component
public class TraceStore {

    private static final int MAX_SIZE = 100;

    private final ConcurrentLinkedDeque<AgentRunTrace> traces = new ConcurrentLinkedDeque<>();

    public void add(AgentRunTrace trace) {
        traces.addLast(trace);
        while (traces.size() > MAX_SIZE) {
            traces.pollFirst();
        }
    }

    public List<AgentRunTrace> getAll() {
        return List.copyOf(traces).reversed();
    }

    public AgentRunTrace getById(String traceId) {
        return traces.stream()
                .filter(t -> t.traceId().equals(traceId))
                .findFirst()
                .orElse(null);
    }

    public int size() {
        return traces.size();
    }

    public void clear() {
        traces.clear();
    }
}

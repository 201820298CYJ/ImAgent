package com.yupi.yuaiagent.harness;

import com.yupi.yuaiagent.harness.model.AgentRunTrace;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理端 Trace 查询接口（调试 / 可观测性）
 */
@RestController
@RequestMapping("/admin/traces")
public class AdminTraceController {

    @Resource
    private TraceStore traceStore;

    @GetMapping
    public List<AgentRunTrace> listTraces() {
        return traceStore.getAll();
    }

    @GetMapping("/{traceId}")
    public AgentRunTrace getTrace(@PathVariable String traceId) {
        return traceStore.getById(traceId);
    }

    @DeleteMapping
    public String clearTraces() {
        traceStore.clear();
        return "已清空所有 trace 记录";
    }
}

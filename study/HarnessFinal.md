# Agent Runtime Harness 快速回顾

## 简历描述

> Agent Runtime Harness：设计 AgentHarness 统一管理 Agent 请求生命周期，通过全链路 Trace 结构化记录各决策节点信息，实现线上问题可排查；工具调度层实现多工具并行执行，工具执行失败时将异常转为结构化反馈传给 LLM 触发自主纠偏，避免单工具故障中断 ReAct 链路。

---

## Part 1：全链路 Trace（可观测性）

### 解决什么问题

用户反馈"回答不对"时，无法定位是哪一步出了问题——意图分类错了？检索没召回？Rerank 排序偏了？还是 LLM 生成幻觉了？没有结构化中间过程记录，排查只能靠猜。

### 我做了什么

**1）设计 AgentHarness 编排层**

在 Controller 和 Agent 之间插入 `AgentHarness`，统一管理请求级生命周期：

```
AiController（仅做 HTTP 映射）
  └── agentHarness.runStream(message, chatId)
        ├── [1] 创建 TraceCollector（请求级追踪上下文）
        ├── [2] 组装 YuManus 并注入 TraceCollector + ToolResilientExecutor
        ├── [3] 委托 yuManus.runStream()（原有 SSE 流式逻辑不变）
        └── [4] SseEmitter.onCompletion 回调 → 收集并持久化 Trace
```

- 改造前 Controller 持有 6 个 @Resource 直接 `new YuManus(...)`，改造后只注入 1 个 AgentHarness
- Harness 是 Spring 单例 @Component，YuManus 仍每请求创建（隔离会话状态）
- 采用组合而非重写：BaseAgent 原有 execute/preProcess/dispatch 完全不变

**2）设计 TraceCollector 收集各决策节点**

追踪覆盖 5 个决策节点：

| 节点 | 记录内容 | 上报位置 |
|------|---------|---------|
| 查询重写 | rewrittenQuery | BaseAgent.preProcess() |
| 意图分类 | intent + confidence | BaseAgent.preProcess() |
| 混合检索 | 15 条 RRF 融合结果（docId + snippet + score） | KnowledgeBaseQueryTool |
| Rerank 精排 | Top5 交叉编码器排序结果 | KnowledgeBaseQueryTool |
| 工具调用 | toolName + args + result + durationMs | ToolCallAgent.act() |

最终汇总为 `AgentRunTrace` record（traceId / conversationId / userQuery / intent / retrievalContext / rerankContext / toolCalls / finalAnswer / tokenEstimate / durationMs / timestamp）。

**3）线程安全方案**

SSE 场景下存在两个线程共享 TraceCollector：

| 问题 | 方案 | 原因 |
|------|------|------|
| 深层单例 Bean 如何获取请求级 Trace | ThreadLocal（TraceContext） | KnowledgeBaseQueryTool 是单例，方法签名受框架约束不能加参数 |
| List 字段跨线程写读 | CopyOnWriteArrayList | 写少（~30次/请求）读少（1次 buildTrace），COW 开销忽略 |
| 标量字段跨线程可见性 | volatile | intent / rewrittenQuery / finalAnswer / endTime |

```
执行线程 (ForkJoinPool)              Servlet 容器线程
  ├── TraceContext.set(tc)
  ├── tc.setRewrittenQuery(...)
  ├── tc.setIntent(...)
  ├── tc.addRetrievalEntry(...)       
  ├── tc.addRerankEntry(...)
  ├── tc.setFinalAnswer(...)
  ├── TraceContext.clear()
  └── sink.complete() ─────────────→  onCompletion()
                                        ├── tc.markEnd()
                                        ├── tc.buildTrace()  ← 读 volatile + COW
                                        └── traceStore.add(trace)
```

**4）存储与查询**

- `TraceStore`：ConcurrentLinkedDeque，上限 100 条 FIFO 淘汰，纯内存（定位为调试工具）
- `AdminTraceController`：GET /admin/traces（列表）、GET /admin/traces/{traceId}（详情）、DELETE /admin/traces（清空）

### 关键文件

| 文件 | 职责 |
|------|------|
| `AgentHarness.java` | 编排层入口，管理请求生命周期 |
| `TraceCollector.java` | 请求级追踪收集器（线程安全） |
| `TraceContext.java` | ThreadLocal 持有者 |
| `TraceStore.java` | 有界内存存储 |
| `AdminTraceController.java` | Admin 查询 API |
| `AgentRunTrace.java` | Trace 记录 record |

---

## Part 2：工具调度层（韧性执行）

### 解决什么问题

1. **多工具串行**：LLM 一轮返回多个工具调用时串行执行，总耗时 = Σ 各工具耗时
2. **无超时保护**：工具卡住（PG 慢查询、外网阻塞）整个 ReAct 循环挂死
3. **异常中断循环**：工具抛异常冒泡为英文栈信息，LLM 读不懂无法纠偏，ReAct 链断裂

### 我做了什么

**1）基于 JDK 21 虚拟线程的并行调度**

`ToolResilientExecutor` 替代 Spring AI 的 `ToolCallingManager` 黑盒串行调用：

```
LLM.think() → [tool1(args1), tool2(args2)]
                      │
          ToolResilientExecutor.executeAll()
                      │
          ┌───────────┴───────────┐
     VirtualThread A         VirtualThread B
     tool1 timeout=5s        tool2 timeout=8s
          │                       │
          └───────────┬───────────┘
                      ▼
          合并为 ToolResponseMessage → 塞回消息上下文
                      │
                      ▼
          LLM 下一轮 think() 读到 → 继续推进或纠偏
```

- 使用 `Executors.newVirtualThreadPerTaskExecutor()`，IO 阻塞时自动解绑载体线程
- 每个工具在独立虚拟线程执行，复合请求耗时从 Σ 降到 max（约 -44%）
- 100 并发请求 × 2 工具 = 200 个并发任务，仅占用 ~8 个载体线程

**2）工具级差异化超时策略（ToolPolicy）**

| 工具 | 超时 | 依据 |
|------|------|------|
| queryKnowledgeBase | 5s | PG + ES + Rerank 内网链路，正常 <2s |
| searchWeb | 8s | 外部搜索 API 走公网 |
| scrapeWebPage | 10s | 抓取真实网页 + DOM 解析 |
| doTerminate | 1s | Agent 内部信号，纯本地逻辑 |
| 未注册工具 | 5s（默认） | 兜底策略 |

以 `Map<String, ToolPolicy>` 声明，扩展新工具改一行代码。

**3）异常统一转结构化反馈（ToolFailureFormatter）**

核心理念：**错误消息也是 Prompt**。

异常分类（`ToolErrorType`）：

| 类型 | 触发条件 | 回喂 LLM 话术（节选） |
|------|---------|-------------------| 
| TIMEOUT | 超过 policy.timeoutMs | "建议简化查询关键词后重新调用；若仍超时，请改用其他工具或基于上下文作答" |
| INVALID_ARG | 参数为空/工具名未找到 | "建议检查该工具的参数格式后重新调用" |
| EXECUTION_ERROR | 下游服务不可用/IO 失败 | "建议优先使用其他工具补全信息；若所有工具均不可用，请基于上下文作答" |

关键实现：`CompletableFuture.handle((result, ex) -> ...)` 吞掉所有异常，异常不向外抛出，而是格式化为 `ToolResponse` 塞回消息上下文。`allOf().join()` 永远不抛错，ReAct 循环不中断。

**4）虚拟线程 ThreadLocal 传播**

坑点：虚拟线程默认不继承父线程 ThreadLocal，直接扔进去 `TraceContext.get()` 返回 null。

解决方案：父线程显式抓取 → 子线程重新 set → finally clear：

```java
TraceCollector parentTrace = TraceContext.get();   // 父线程抓取引用
CompletableFuture.supplyAsync(() -> {
    if (parentTrace != null) TraceContext.set(parentTrace);   // 虚拟线程内恢复
    try { return invokeTool(toolCall, availableTools); }
    finally { TraceContext.clear(); }                          // 归还前清理
}, virtualThreadExecutor);
```

**5）向后兼容**

`ToolCallAgent.act()` 保留双路径：toolResilientExecutor != null 走韧性路径，否则走原有 ToolCallingManager 串行逻辑。不经过 Harness 创建的 Agent 自动走 legacy。

### 关键文件

| 文件 | 职责 |
|------|------|
| `ToolResilientExecutor.java` | 并行调度 + 超时 + 异常回喂 |
| `ToolPolicy.java` | 工具级差异化超时配置 |
| `ToolFailureFormatter.java` | 异常 → LLM 可读中文反馈 |
| `ToolErrorType.java` | 错误分类枚举 |

---

## Part 3：面试 QA

### Trace 相关

**Q1: AgentHarness 的定位是什么？和 Service/Manager 有什么区别？**

A: Harness 是 Controller 与 Agent 之间的编排层，负责请求生命周期管理（创建 Trace → 组装 Agent → 委托执行 → 回调存储）。与 Service 的区别：Service 自己做业务逻辑，Harness 不改变 Agent 行为，只在执行前后做编排和观测。类比 JUnit 是测试 Harness——管理生命周期、收集结果，但不写业务代码。

**Q2: 为什么用 ThreadLocal 而不是方法参数透传 TraceCollector？**

A: 两个原因：
1. `KnowledgeBaseQueryTool` 是 @Bean 注册的 Spring 单例，方法签名由 Spring AI 工具框架约束，不能加参数
2. 执行模型是 CompletableFuture.runAsync() 把整个 Agent 链放在同一个线程，ThreadLocal 天然请求隔离

BaseAgent.execute() 开头 set，finally 中 clear，链路中任何组件通过 `TraceContext.get()` 拿到。

**Q3: CopyOnWriteArrayList 不怕性能问题吗？**

A: 不怕。COW 性能问题出在"频繁写大列表"。我们场景：每请求最多写 ~30 次（15 检索 + 5 Rerank + 若干工具），列表不超几十条，读只 1 次（buildTrace）。这是 COW 最佳场景——写少读少，天然线程安全无锁。

**Q4: SseEmitter.onCompletion 一定在 Agent 执行完之后触发吗？怎么保证数据一致？**

A: 是的。调用链：BaseAgent.execute() → sink.complete() → sseEmitter.complete() → 触发 onCompletion。sink.complete() 在 try 块最后一行，此时所有追踪数据已写入。跨线程可见性通过 volatile 字段 + COW 的内存屏障保证 happens-before。

**Q5: TraceStore 为什么只存 100 条？上生产怎么改？**

A: 定位为开发调试工具，100 条够排查近期问题。上生产三个改造方向：
1. 存储后端：换 ES/ClickHouse，支持全文检索和分析
2. 采样策略：高 QPS 下按比例采样，或只存异常/慢请求
3. 关联能力：traceId 透传下游，打通跨服务调用链（对接 SkyWalking/Jaeger）

**Q6: 这套 Trace 对性能有多大影响？**

A: 几乎为零。额外开销：5 次 volatile 写 + ~30 次 COW add（微秒级）+ 1 次 buildTrace（亚毫秒级）+ TraceStore.add（纳秒级）。对比 Agent 单次请求 2-5s 的 LLM 调用 + 网络 IO，追踪开销 < 0.01%。

---

### 工具调度相关

**Q7: 为什么用虚拟线程而不是传统线程池？**

A: 工具调用高度 IO 密集（PG/ES/HTTP）。传统线程池两难：线程少则阻塞导致吞吐低，线程多则内存大、切换成本高。虚拟线程是 M:N 模型，IO 阻塞时自动解绑载体线程。100 并发 × 2 工具 = 200 任务，仅占 ~8 个载体线程。语义上 `newVirtualThreadPerTaskExecutor()` 也比 ForkJoinPool 更符合"来一个开一个"的工具调用场景。

**Q8: 为什么不能统一超时阈值？**

A: 统一阈值两难——定短（3s）会误杀 scrapeWebPage（正常 5-8s）；定长（15s）则 queryKnowledgeBase 卡住要等很久才熔断。差异化超时反映每个工具真实的响应时间分布，以 Map 声明，扩展新工具改一行。

**Q9: 工具失败后 LLM 怎么知道该重试、换工具还是放弃？**

A: 反馈消息的结构化。每种错误类型对应"原因 + 建议"两段式中文话术。LLM 下一轮 think() 读到后根据"建议"自主决策。本质是把 Prompt Engineering 从 System Prompt 扩展到 Tool Response 层面——**错误消息也是 Prompt**。

**Q10: 虚拟线程 ThreadLocal 冲突怎么解决的？**

A: 虚拟线程默认不继承父线程 ThreadLocal。解决：父线程提交前显式抓取 TraceCollector 引用，虚拟线程内部重新 `TraceContext.set(parentTrace)` + finally `TraceContext.clear()`。JDK 21 的 ScopedValue 是更优雅方案但还在预览阶段，暂未采用。

**Q11: 异常不抛出，ReAct 循环怎么正常结束？**

A: ReAct 循环结束条件是 LLM 调用 `doTerminate` 工具或达到 maxSteps 上限——与工具是否异常无关。工具失败反馈塞回消息上下文后，LLM 自主决定重试/换工具/直接作答，循环自然推进直到终止条件。

**Q12: 向后兼容怎么保证？如果 ToolResilientExecutor 出 bug 怎么办？**

A: `ToolCallAgent.act()` 保留双路径——toolResilientExecutor != null 走韧性路径，否则走原有 ToolCallingManager 串行。不经过 Harness 创建 Agent 时字段为 null，自动 fallback。生产出问题时可通过配置快速切回 legacy。

**Q13: 如果面试官追问"你这个并行调度和 Spring AI 原生的有什么区别"？**

A: Spring AI 的 `ToolCallingManager.executeToolCalls()` 是串行调用 + 统一异常处理（直接抛或返回英文错误消息）。我自建执行层的三个差异：
1. **并行**：同轮多工具虚拟线程并行，耗时 Σ→max
2. **差异化超时**：每个工具独立计时独立熔断
3. **异常语义化**：异常不中断循环，转为 LLM 可理解的中文建议驱动纠偏

本质是将工具调度从"框架黑盒"升级为"可控可观测的韧性调度层"。

---

## 一句话总结

**Trace 做的是"看得见"**——让 Agent 每步决策可追溯可排查；**工具调度做的是"打不死"**——多工具并行加速 + 单工具故障不中断 + LLM 自主纠偏恢复。两者结合实现了 Agent 的工程化运行保障。
# Agent Runtime Harness 设计

## 简历描述

> Agent Runtime Harness 设计：设计 AgentHarness 统一管理 Agent 请求生命周期，通过全链路 Trace 结构化记录各决策节点中间状态实现线上问题可追溯可复现；工具调度层实现多工具并行执行与差异化超时，工具失败时异常转为结构化反馈回喂 LLM 触发自主纠偏，避免单工具故障中断 ReAct 链路。

---

## 一、做了什么

整体上做了两件核心的事：

### 1. 全链路追踪（可观测性）

**问题**：用户反馈"回答不对"时，无法定位是意图分类错了、检索没召回、Rerank 排错了、还是 LLM 生成出了问题。

**方案**：设计 `AgentHarness` 作为 Controller 与 Agent 之间的编排层，在请求入口创建 `TraceCollector`，注入 Agent 执行链路，各决策节点（查询重写、意图分类、混合检索、Rerank、工具调用）主动上报中间状态，请求结束后构建完整 `AgentRunTrace` 存入内存，暴露 Admin API 供查询。

**调用链路**：
```
AiController.doChatWithManus(message, chatId)
  └── agentHarness.runStream(message, chatId)
        ├── 创建 TraceCollector（请求级追踪上下文）
        ├── 组装 YuManus 并注入 TraceCollector
        ├── 委托 yuManus.runStream()（原有 SSE 流式逻辑不变）
        └── 注册 SseEmitter.onCompletion 回调 → 收集并存储 Trace
```

**关键设计**：
- `AgentHarness` 是 Spring 单例 `@Component`，YuManus 仍每请求创建（隔离会话状态）
- 改造前 Controller 持有 6 个依赖手动组装 Agent，改造后只注入 1 个 AgentHarness
- 采用组合而非重写：BaseAgent 原有 execute/preProcess/dispatch 流程完全不变

**线程安全方案**：
- 深层单例组件（如 KnowledgeBaseQueryTool）通过 `ThreadLocal`（TraceContext）获取当前请求的 TraceCollector
- List 字段用 `CopyOnWriteArrayList`（写少读少，COW 开销可忽略）
- 标量字段用 `volatile` 保证 SSE 执行线程与 Servlet 回调线程间可见性

### 2. 工具调度层（韧性执行）

**问题**：
- 多工具串行执行，总耗时 = Σ 各工具耗时
- 无超时保护，工具卡住整个 ReAct 循环挂死
- 异常直接冒泡为英文栈信息，LLM 读不懂无法纠偏

**方案**：`ToolResilientExecutor` 替代 Spring AI 的 `ToolCallingManager` 黑盒串行调用，实现并行调度 + 差异化超时 + 异常回喂。

**执行链路**：
```
LLM.think() → 决定调用 [tool1(args1), tool2(args2)]
                        │
            ToolResilientExecutor.executeAll()
                        │
            ┌───────────┴───────────┐
       Virtual Thread A        Virtual Thread B
       tool1 timeout=5s        tool2 timeout=8s
            │                       │
     成功/超时/异常           成功/超时/异常
            │                       │
            └───────────┬───────────┘
                        ▼
            合并为 ToolResponseMessage 塞回消息上下文
                        │
                        ▼
            LLM 下一轮 think() 读到反馈 → 自主纠偏
```

**工具级差异化超时策略**：

| 工具 | 超时 | 依据 |
|------|------|------|
| queryKnowledgeBase | 5s | PG + ES + Rerank 三段内网链路，正常 <2s |
| searchWeb | 8s | 外部搜索 API 走公网 |
| scrapeWebPage | 10s | 抓取真实网页 + DOM 解析，大响应体 |
| doTerminate | 1s | Agent 内部信号，纯本地逻辑 |

**异常分类与回喂**（ToolFailureFormatter）：
- `TIMEOUT` → "工具 X 执行超时...建议简化查询或换工具"
- `INVALID_ARG` → "参数错误...建议检查格式重新调用"
- `EXECUTION_ERROR` → "执行失败...建议优先使用其他工具补全信息"

核心理念：**错误消息也是 Prompt**，LLM 根据"建议"部分自主决定纠偏策略。

**虚拟线程与 ThreadLocal 冲突解决**：虚拟线程默认不继承父线程 ThreadLocal，父线程显式抓取 TraceCollector 引用，在虚拟线程内部重新 set + finally clear。

**向后兼容**：`ToolCallAgent.act()` 保留 legacy 路径，若 `toolResilientExecutor` 未注入则走原有 ToolCallingManager 串行逻辑。

---

## 二、文件清单

| 文件 | 职责 |
|------|------|
| `AgentHarness.java` | 编排层入口，管理请求生命周期 |
| `TraceCollector.java` | 请求级追踪收集器（线程安全） |
| `TraceContext.java` | ThreadLocal 持有者 |
| `TraceStore.java` | 有界内存存储（ConcurrentLinkedDeque，上限100条） |
| `AdminTraceController.java` | Admin API（GET/DELETE traces） |
| `AgentRunTrace.java` | Trace 记录 record |
| `ToolResilientExecutor.java` | 并行调度 + 超时 + 异常回喂 |
| `ToolPolicy.java` | 工具级差异化超时配置 |
| `ToolFailureFormatter.java` | 异常 → LLM 中文反馈格式化 |
| `ToolErrorType.java` | 工具错误分类枚举 |

---

## 三、面试 QA

### Q1: AgentHarness 的定位是什么？为什么不直接在 Controller 里做？

**A**: AgentHarness 是 Controller 与 Agent 之间的编排层，职责是请求生命周期管理（创建 Trace → 组装 Agent → 委托执行 → 回调存储 Trace）。改造前 Controller 持有 6 个依赖（ChatModel、工具数组、意图分类器、查询改写器、知识库工具、Redis 记忆）直接组装 Agent，HTTP 协议处理和 Agent 编排混在一起。分离后 Controller 只做 HTTP 映射，Harness 统一管编排和观测——关注点分离，便于独立演进和测试。

### Q2: 全链路 Trace 怎么做到线程安全的？

**A**: SSE 流式场景下有两个线程参与：
- **执行线程**（CompletableFuture.runAsync 的线程池线程）：运行 Agent 全部逻辑，写入 TraceCollector
- **Servlet 容器线程**：SseEmitter.onCompletion 回调中读取 TraceCollector 并 buildTrace

解决方案三板斧：
1. `ThreadLocal`（TraceContext）：让深层单例 Bean（如 KnowledgeBaseQueryTool）无需传参就能拿到当前请求的 TraceCollector
2. `CopyOnWriteArrayList`：List 字段写少读少，COW 开销可忽略
3. `volatile`：标量字段（intent、rewrittenQuery、finalAnswer、endTime）保证跨线程可见性

### Q3: 为什么用 ThreadLocal 而不是直接把 TraceCollector 当方法参数透传？

**A**: 因为 `KnowledgeBaseQueryTool` 是 `@Bean` 注册的 Spring 单例，被所有请求共享。它的方法签名由 Spring AI 工具框架约束，不能随意加参数。而我们的执行模型是 `CompletableFuture.runAsync()` 把整个 Agent 执行链放在同一个线程中，ThreadLocal 天然是请求隔离的。`BaseAgent.execute()` 开头 set，finally 中 clear，链路中任何组件都可通过 `TraceContext.get()` 拿到。

### Q4: TraceStore 为什么只存 100 条不持久化？

**A**: 设计定位是开发调试工具，不是审计系统。100 条内存足够排查最近问题。如果需要持久化（如生产 A/B 分析），只需把 `TraceStore.add()` 改为写 DB——Trace 数据结构已标准化，存储后端可替换。用 `ConcurrentLinkedDeque` 做 FIFO 淘汰，超出上限时 pollFirst 最旧的。

### Q5: 工具并行调度相比改造前的收益是什么？

**A**: 改造前 `ToolCallingManager.executeToolCalls()` 是串行的，LLM 一轮返回 N 个工具时总耗时 = Σ 各工具耗时。改造后每个工具在独立虚拟线程中执行，总耗时 = max(各工具耗时)。例如 queryKnowledgeBase(1.5s) + searchWeb(1.2s) 从 2.7s 降到 1.5s，降幅约 44%。

### Q6: 为什么每个工具设置不同超时，统一 10s 不行吗？

**A**: 统一阈值两难：
- 定短（3s）：scrapeWebPage 正常也要 5-8s，会被误杀
- 定长（15s）：queryKnowledgeBase 卡住了要等 15s 才熔断，用户体感差

按 IO 特征差异化才合理：KB 是内网三段链路 5s、外网搜索 8s、网页抓取 10s、内部信号 1s。每个阈值都反映该工具真实的响应时间分布。策略以 `Map<String, ToolPolicy>` 声明，扩展新工具改一行，未注册的走默认 5s。

### Q7: 工具失败后 LLM 怎么知道该怎么做？

**A**: 关键在于反馈消息的结构化。每种错误类型对应"原因 + 建议"两段式中文反馈：
- 超时 → 建议简化参数或换工具
- 参数错误 → 建议修正格式重试
- 执行异常 → 建议基于上下文作答或换工具

反馈作为 `ToolResponseMessage` 塞回消息上下文，LLM 下一轮 think() 读到后根据"建议"自主决策。本质是把 Prompt Engineering 从 System Prompt 扩展到 Tool Response 层面——**错误消息也是 Prompt**。

### Q8: 虚拟线程和 ThreadLocal 有冲突怎么解决的？

**A**: 虚拟线程默认不继承父线程 ThreadLocal。如果直接把工具调用扔进虚拟线程，内部 `TraceContext.get()` 返回 null，Trace 链路就断了。

解决方案：在 `ToolResilientExecutor.executeAll()` 提交任务前，父线程显式抓取 TraceCollector 引用，在虚拟线程内部重新 set + finally clear：

```java
TraceCollector parentTrace = TraceContext.get();   // 父线程抓取
CompletableFuture.supplyAsync(() -> {
    if (parentTrace != null) TraceContext.set(parentTrace);   // 子线程恢复
    try { return invokeTool(...); }
    finally { TraceContext.clear(); }                          // 归还前清理
}, virtualThreadExecutor);
```

JDK 21 也有 `ScopedValue`（预览特性）作为更优雅方案，但生产项目暂未采用。

### Q9: 异常不抛出不会有问题吗？ReAct 循环怎么结束？

**A**: `CompletableFuture.handle((r, ex) -> ...)` 把所有异常吞掉转为 LLM 可读的反馈文本，`allOf().join()` 永远不抛错。ReAct 循环的结束条件是 LLM 调用 `doTerminate` 工具或达到 maxSteps 上限——和工具是否异常无关。LLM 读到失败反馈后自主决定是重试、换工具还是直接作答，循环自然推进。

### Q10: 向后兼容怎么保证的？

**A**: `ToolCallAgent.act()` 里同时保留两条路径：
- 路径 A（韧性）：若 `toolResilientExecutor != null`，走并行 + 超时 + 回喂
- 路径 B（legacy）：否则走原有 `ToolCallingManager` 串行

`toolResilientExecutor` 由 AgentHarness 通过 setter 注入 YuManus。如果不经过 Harness 直接创建 Agent（比如测试场景），字段为 null，自动走 legacy，行为和改造前完全一致。

### Q11: CopyOnWriteArrayList 性能会不会有问题？

**A**: 不会。COW 的性能问题在于"频繁写入大列表"时每次 add 都拷贝整个数组。我们的场景是每请求最多写 15（检索）+ 5（Rerank）+ 若干工具调用 ≈ 30 次，列表永远不超过几十条。读只发生一次（onCompletion 中 buildTrace）。这是 COW 的最佳适用场景——写少读少，天然线程安全无锁。

### Q12: 这套 Harness 对性能有多大影响？

**A**: 几乎为零。额外开销：
- 5 次 volatile 写 + ~30 次 COW add：微秒级
- 1 次 buildTrace（遍历 list + 字符串估算 token）：亚毫秒级
- TraceStore.add（ConcurrentLinkedDeque.addLast）：纳秒级

对比 Agent 单次请求 2-5s 的 LLM 调用 + 网络 IO，追踪开销 < 0.01%。

### Q13: 如果让你把这套 Trace 上生产，你会怎么改？

**A**: 三个方向：
1. **存储后端**：TraceStore 从内存 Deque 换为 ES/ClickHouse，支持全文检索和分析
2. **采样策略**：高 QPS 下不是每个请求都存，按比例采样或只存异常/慢请求
3. **关联能力**：traceId 透传到下游服务（ES/DashScope），打通跨服务调用链，和 SkyWalking/Jaeger 对接
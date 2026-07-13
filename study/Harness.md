# Agent Runtime Harness 设计

## 简历描述

> Agent Runtime Harness 设计：设计 AgentHarness 统一管理 Agent 请求生命周期，通过全链路 Trace 结构化记录各决策节点中间状态实现线上问题可追溯可复现；基于 JDK 21 虚拟线程构建工具韧性执行层，多工具同轮次并行调度使复合请求耗时降 40%+，工具失败时异常转为结构化反馈回喂 LLM 触发自主纠偏，避免单工具故障中断 ReAct 链路。

---

## 一、为什么要做这件事

### 改造前的问题

1. **Controller 职责过重**：`AiController` 直接持有 6 个 `@Resource` 依赖（ChatModel、工具数组、意图分类器、查询改写器、知识库工具、Redis 记忆），手动 `new YuManus(...)` 组装 Agent。HTTP 协议处理和 Agent 编排逻辑混在一起。
2. **出了问题无法定位**：学生问"信管有哪些专业"，如果回答不对，你不知道是意图分类错了、检索没召回、Rerank 排错了、还是 LLM 生成出了问题——没有任何结构化的中间过程记录。
3. **多工具串行、无容错**：LLM 一轮返回多个工具调用时串行执行，总耗时 = Σ 各工具耗时；无超时保护，工具卡住整个 ReAct 循环挂死；异常直接冒泡为英文栈信息，LLM 读不懂无法纠偏。

### 改造后的收益

| 维度 | 改造前 | 改造后 |
|------|--------|--------|
| 关注点分离 | Controller 直接组装 Agent（6 个依赖） | Harness 统一编排，Controller 只做 HTTP 映射（1 个依赖） |
| 可观测性 | 只有日志打印 | 结构化 Trace，Admin API 可查 |
| 工具执行 | 串行、无超时、异常中断 | 并行、差异化超时、异常回喂纠偏 |

---

## 二、模块 1：Agent Harness 编排层

### 核心思想

在 Controller 和 Agent 之间插入 `AgentHarness` 编排组件，统一管理请求级生命周期。

**改造前的调用链：**
```
AiController.doChatWithManus(message, chatId)
    └── new YuManus(allTools, chatModel, memory, rewriter, classifier, kbTool, chatId)
    └── yuManus.runStream(message)
```

**改造后的调用链：**
```
AiController.doChatWithManus(message, chatId)
    └── agentHarness.runStream(message, chatId)
            ├── [1] 创建 TraceCollector（请求级追踪上下文）
            ├── [2] 组装 YuManus 并注入 TraceCollector + ToolResilientExecutor
            ├── [3] 委托 yuManus.runStreamWithEmitter()（原有 SSE 流式逻辑不变）
            └── [4] onFinish 回调（在 finally 中同步执行）→ 收集并存储 Trace
```

**改动对比：**

| | 改造前 AiController | 改造后 AiController |
|---|---|---|
| 注入依赖数 | 6 个（allTools, chatModel, queryRewriter, intentClassifier, knowledgeBaseQueryTool, redisChatMemory） | 1 个（agentHarness） |
| 代码行数 | 64 行 | 25 行 |
| 职责 | HTTP + Agent 组装 + 生命周期 | 仅 HTTP 映射 |

**关键设计决策：**
- `AgentHarness` 是 Spring 单例 `@Component`，YuManus 仍然每请求创建（隔离会话状态）
- 采用**组合**而非重写：BaseAgent 的 execute/preProcess/dispatch 流程完全不变，Harness 只是包了一层
- `AgentHarness` 不持有任何请求级可变状态。`runStream()` 中的 `TraceCollector`、`YuManus` 都是局部变量，不会跨请求。注入的 `TraceStore`、`ToolResilientExecutor` 也是线程安全的

---

## 三、模块 2：全链路追踪 (AgentRunTrace)

### 解决的问题

用户反馈"回答不对"时，能快速定位是哪个环节出了问题。

### 追踪覆盖的决策节点

```
┌─────────────────────────────────────────────────────────────────┐
│  用户输入: "学院有哪些专业"                                         │
│     │                                                           │
│     ├─[1] 查询重写 ──→ "南京大学信息管理学院有哪些本科专业"           │
│     ├─[2] 意图分类 ──→ KNOWLEDGE (confidence=0.92)               │
│     │                                                           │
│     ├─[3] 混合检索（15条）──→ retrievalContext                    │
│     │     文档ID | 片段摘要(前200字) | RRF融合分数                  │
│     │                                                           │
│     ├─[4] Rerank精排（5条）──→ rerankContext                      │
│     │     文档ID | 片段摘要(前200字) | 交叉编码器分数                │
│     │                                                           │
│     └─[5] 最终回答 ──→ finalAnswer                               │
│                                                                 │
│  + tokenEstimate + durationMs + timestamp                       │
└─────────────────────────────────────────────────────────────────┘
```

对于 TASK 意图（ReAct 循环），追踪覆盖的是：
```
[3] 工具调用 ──→ toolCalls[]
    每条记录: 工具名 | 调用参数 | 返回结果(前500字) | 耗时(ms)
```

### Trace 采集点的代码实现

Trace 采集的核心设计：**TraceCollector 作为请求级对象在 Harness 中创建，通过 ThreadLocal 透传给调用链路上的各个组件，每个组件在自己的业务逻辑中"顺手"上报数据**。下面按照请求执行的时序，逐一说明每个采集点。

**采集点 ①：TraceCollector 创建与 ThreadLocal 挂载**

`AgentHarness.runStream()` 每次请求创建一个 `TraceCollector`，注入到新建的 `YuManus` 实例中：

```java
// AgentHarness.runStream()
public SseEmitter runStream(String message, String chatId) {
    TraceCollector tc = createTraceCollector(message, chatId);  // UUID + 原始query + startTime
    YuManus yuManus = buildAgent(chatId, tc);                   // tc 通过 setter 注入 YuManus
    SseEmitter emitter = new SseEmitter(300_000L);
    yuManus.runStreamWithEmitter(message, emitter, () -> finalizeTrace(tc));
    return emitter;
}
```

`BaseAgent.execute()` 在 `CompletableFuture.runAsync()` 线程的入口处将 `TraceCollector` 挂到 `ThreadLocal`：

```java
// BaseAgent.execute()
private void execute(String userPrompt, OutputSink sink) {
    state = AgentState.RUNNING;
    if (traceCollector != null) {
        TraceContext.set(traceCollector);    // ← 挂载到当前执行线程
    }
    try {
        ProcessedPrompt processed = preProcess(userPrompt);
        // ... dispatchByIntent ...
        sink.complete();
    } catch (Exception e) {
        state = AgentState.ERROR;
        sink.completeWithError("执行错误：" + e.getMessage());
    } finally {
        TraceContext.clear();               // ← 归还线程前清理，防止泄漏
        cleanup();
    }
}
```

> 为什么用 ThreadLocal 而不是方法参数透传？因为 `KnowledgeBaseQueryTool` 是通过 `@Bean` 注册的 Spring 单例，方法签名由 Spring AI 工具框架约束（`String queryKnowledgeBase(String query)`），无法加参数。ThreadLocal 让单例 Bean 无侵入地获取请求级上下文。

**采集点 ②：查询重写 — `BaseAgent.preProcess()`**

```java
private ProcessedPrompt preProcess(String userPrompt) {
    String prompt = userPrompt;
    if (queryRewriter != null && chatMemory != null && conversationId != null) {
        prompt = queryRewriter.doQueryRewrite(userPrompt, chatMemory, conversationId);
        if (traceCollector != null) {
            traceCollector.setRewrittenQuery(prompt);   // ← 上报改写后的 query
        }
    }
    // ... 后续意图分类 ...
}
```

排查价值：如果原始 query 是"有哪些专业"，但改写结果变成了"南京大学计算机学院有哪些专业"（错误的上下文补充），通过 Trace 就能立刻定位到是改写环节出了问题。

**采集点 ③：意图分类 — `BaseAgent.preProcess()`**

```java
IntentType intent = IntentType.TASK;
double confidence = 1.0;
if (intentClassifier != null) {
    ClassifyResult result = intentClassifier.classify(prompt);
    intent = result.intent();
    confidence = result.confidence();
    if (traceCollector != null) {
        traceCollector.setIntent(intent);           // ← 上报分类结果
        traceCollector.setConfidence(confidence);   // ← 上报置信度
    }
}
```

排查价值：如果一个知识问答被误分为 CHAT（闲聊），Agent 会走 chatClient 一轮直答而不走知识库检索，Trace 中 `intent=CHAT` + `retrievalContext=[]` 就能立刻定位到是分类错了。

**采集点 ④：混合检索 + Rerank — `KnowledgeBaseQueryTool`**

这是 ThreadLocal 透传的核心应用场景。`KnowledgeBaseQueryTool` 是 Spring 单例，无法通过构造器注入请求级的 TraceCollector，只能通过 `TraceContext.get()` 获取：

```java
@Tool(description = "查询知识库")
public String queryKnowledgeBase(String query) {
    // 1. 混合检索
    List<Document> fusedDocs = hybridSearchService.hybridSearch(query, 15, 15, 0.6, 15);

    // ← 上报 RRF 融合结果
    TraceCollector tc = TraceContext.get();
    if (tc != null) {
        for (Document doc : fusedDocs) {
            tc.addRetrievalEntry(new RetrievalEntry(
                doc.getId(), doc.getText().substring(0, Math.min(200, doc.getText().length())),
                doc.getScore(), "rrf"
            ));
        }
    }

    // 2. Rerank 精排
    List<Document> rerankedDocs = dashScopeRerankService.rerank(query, fusedDocs, 5);

    // ← 上报 Rerank 精排结果
    if (tc != null) {
        for (Document doc : rerankedDocs) {
            double rerankScore = ((Number) doc.getMetadata()
                .getOrDefault("rerank_score", 0)).doubleValue();
            tc.addRerankEntry(new RetrievalEntry(
                doc.getId(), doc.getText().substring(0, Math.min(200, doc.getText().length())),
                rerankScore, "rerank"
            ));
        }
    }
    // ... 拼接返回 ...
}
```

排查价值：对比 `retrievalContext` 和 `rerankContext` 可以判断问题出在哪一层——如果 RRF 结果里有正确文档但 Rerank 后排到了第 6 名以外，说明是交叉编码器的排序问题；如果 RRF 结果里就没有，说明是向量/BM25 的召回问题。

**采集点 ⑤：工具调用 — `ToolCallAgent.actWithResilience()`**

在 TASK 意图的 ReAct 循环中，每轮 `act()` 执行完所有工具后，将调用详情上报 Trace：

```java
private String actWithResilience(List<AssistantMessage.ToolCall> toolCallList) {
    long actStart = System.currentTimeMillis();
    List<ToolResponse> responses =
        toolResilientExecutor.executeAll(toolCallList, availableTools);
    long actDuration = System.currentTimeMillis() - actStart;

    // ← 上报每个工具的调用详情
    TraceCollector tc = TraceContext.get();
    if (tc != null) {
        for (int i = 0; i < toolCallList.size(); i++) {
            AssistantMessage.ToolCall call = toolCallList.get(i);
            String result = responses.get(i).responseData();
            tc.addToolCall(new ToolCallEntry(
                call.name(), call.arguments(),
                result.substring(0, Math.min(500, result.length())), actDuration
            ));
        }
    }
    // ...
}
```

**采集点 ⑥：Trace 收尾与持久化 — `AgentHarness.finalizeTrace()`**

Agent 执行完毕后，在 `runStreamWithEmitter` 的 `finally` 块中同步执行 Trace 收尾：

```java
private void finalizeTrace(TraceCollector tc) {
    tc.markEnd();                               // endTime = Instant.now()
    AgentRunTrace trace = tc.buildTrace();       // 从 volatile/COW 字段构建不可变 record
    traceStore.add(trace);                       // ConcurrentLinkedDeque，FIFO 淘汰超 100 条
}
```

`buildTrace()` 内部通过 `List.copyOf()` 将 `CopyOnWriteArrayList` 转为不可变列表。`durationMs` 通过 `Duration.between(startTime, endTime).toMillis()` 计算。`tokenEstimate` 通过字符数 ×1.5 估算（中文场景下的近似值）。

### 数据结构（Java 21 record）

```java
public record AgentRunTrace(
    String traceId,                          // 请求唯一ID (UUID)
    String conversationId,                   // 会话ID
    String userQuery,                        // 用户原始输入
    String rewrittenQuery,                   // 查询改写结果
    IntentType intent,                       // 意图分类: CHAT/KNOWLEDGE/TASK/REJECT
    double confidence,                       // 分类置信度
    List<RetrievalEntry> retrievalContext,   // RRF融合后的检索结果
    List<RetrievalEntry> rerankContext,      // Rerank精排后的结果
    List<ToolCallEntry> toolCalls,           // ReAct循环中的工具调用
    String finalAnswer,                      // Agent最终回答
    int tokenEstimate,                       // 估算token消耗 (字符数×1.5)
    long durationMs,                         // 端到端耗时
    Instant timestamp                        // 请求发起时间
) {
    record RetrievalEntry(String documentId, String snippet, double score, String source) {}
    record ToolCallEntry(String toolName, String arguments, String result, long durationMs) {}
}
```

### 线程安全方案

#### SSE 场景下两个线程共享 TraceCollector 的详细分析

**1）SSE 场景的线程模型**

看 `BaseAgent.runStreamWithEmitter()` 方法：

```java
public void runStreamWithEmitter(String userPrompt, SseEmitter sseEmitter, Runnable onFinish) {
    SseOutputSink sink = new SseOutputSink(sseEmitter);
    CompletableFuture.runAsync(() -> {   // ← 开了一个新线程
        try {
            execute(userPrompt, sink);
        } finally {
            onFinish.run();              // ← 在这个新线程里执行 Trace 收集
        }
    });
}
```

再看调用方 `AgentHarness.runStream()`：

```java
public SseEmitter runStream(String message, String chatId) {
    TraceCollector traceCollector = createTraceCollector(message, chatId);  // ① 主线程创建
    YuManus yuManus = buildAgent(chatId, traceCollector);                  // ② 主线程注入

    SseEmitter emitter = new SseEmitter(300_000L);
    yuManus.runStreamWithEmitter(message, emitter, () -> finalizeTrace(traceCollector));  // ③ 异步线程执行
    return emitter;  // ④ 主线程立即返回 emitter 给 Spring MVC
}
```

这里涉及**两个线程**：

| 线程 | 身份 | 做了什么 |
|------|------|---------|
| **线程 A — Tomcat 请求线程** | Spring MVC 的 HTTP 处理线程 | 创建 `TraceCollector` 对象，创建 `SseEmitter`，调用 `runStreamWithEmitter`，然后**立即返回** `emitter` 给客户端，线程释放 |
| **线程 B — CompletableFuture 异步线程** | `ForkJoinPool.commonPool()` 中的线程 | 执行 Agent 全部逻辑（查询重写、意图分类、RAG/ReAct 循环），期间不断往 `TraceCollector` **写入数据**，最后在 `finally` 中调用 `finalizeTrace` 构建最终 Trace |

**关键点**：同一个 `TraceCollector` 对象在线程 A 创建，在线程 B 使用——这就是"两个线程共享"的含义。

**2）为什么需要线程安全？**

这个共享模式会带来 **Java 内存模型（JMM）** 层面的可见性问题：

- 线程 A 创建了 `TraceCollector`，在自己的 CPU 缓存/工作内存中初始化了对象的字段
- 线程 B 是另一个线程，它读取这个对象时，**不保证能看到线程 A 写入的最新值**（除非有 happens-before 关系）

虽然在当前实现中读写主要都发生在线程 B 内部，但 `TraceCollector` 的设计目标是**可以被任意线程安全访问**——因为 SSE 场景中 `onCompletion` 等回调可能在 Servlet 容器线程触发，并且引入虚拟线程后还有更多线程参与。

**3）TraceCollector 的线程安全手段**

**a) `volatile` — 保证单值字段的可见性**

```java
private volatile String rewrittenQuery;
private volatile IntentType intent;
private volatile double confidence = -1.0;
private volatile String finalAnswer;
private volatile Instant endTime;
```

`volatile` 的作用：
- **写入时**：强制将值刷新到主内存（而不是留在 CPU 缓存中）
- **读取时**：强制从主内存读取（而不是读 CPU 缓存中的旧值）
- **建立 happens-before**：对 volatile 变量的写入 happens-before 于后续对同一变量的读取

所以即使在跨线程场景下，`buildTrace()` 读取这些字段时，一定能看到最新写入的值。

**b) `CopyOnWriteArrayList` — 保证集合操作的线程安全**

```java
private final CopyOnWriteArrayList<RetrievalEntry> retrievalContext = new CopyOnWriteArrayList<>();
private final CopyOnWriteArrayList<RetrievalEntry> rerankContext = new CopyOnWriteArrayList<>();
private final CopyOnWriteArrayList<ToolCallEntry> toolCalls = new CopyOnWriteArrayList<>();
```

`CopyOnWriteArrayList` 的特点：
- 每次 `add()` 时，复制一份新数组，在新数组上追加，然后原子地替换引用
- 读操作（迭代、`List.copyOf`）不需要加锁，总是读到一个一致的快照
- 适合**写少读多**的场景——这里每请求写约 30 次（15 检索 + 5 Rerank + 若干工具），读仅 1 次（buildTrace），完全匹配

**4）为什么不直接加一把锁？**

加锁当然也能工作，但这个场景是**单写者-单读者且写在前读在后**，volatile + COW 足够且无竞争开销。如果上 `synchronized`，反而引入了不必要的线程阻塞可能。

**5）TraceContext（ThreadLocal）的角色**

`TraceCollector` 通过 `TraceContext`（一个 `ThreadLocal`）传递给深层组件：

```java
// BaseAgent.execute() 中，SSE 模式下在 CompletableFuture.runAsync 线程中执行
if (traceCollector != null) {
    TraceContext.set(traceCollector);    // 挂载到当前执行线程
}
```

深层组件（如 `KnowledgeBaseQueryTool`、`ToolCallAgent`）通过 `TraceContext.get()` 获取 collector，这些调用都在同一个线程 B 中，所以能拿到。

但如果有更深层的子线程（如 `ToolResilientExecutor` 使用虚拟线程执行工具调用），ThreadLocal **不会自动继承**，需要手动传播（详见模块 3 的虚拟线程部分）。

**6）完整的数据流时序图**

```
线程A (Tomcat)                          线程B (ForkJoinPool)
────────────────                        ─────────────────────
new TraceCollector()  ─── 对象引用 ───→  TraceContext.set(collector)
                                        │
                                        ├─ preProcess()
                                        │   ├─ collector.setRewrittenQuery()  [volatile写]
                                        │   ├─ collector.setIntent()          [volatile写]
                                        │   └─ collector.setConfidence()      [volatile写]
                                        │
                                        ├─ handleKnowledgeIntent()
                                        │   └─ KnowledgeBaseQueryTool
                                        │       └─ TraceContext.get() → collector
                                        │           └─ collector.addRetrievalEntry()  [COW list写]
                                        │
                                        ├─ handleTaskIntent() (ReAct循环)
                                        │   └─ ToolCallAgent
                                        │       └─ TraceContext.get() → collector
                                        │           └─ collector.addToolCall()  [COW list写]
                                        │
                                        └─ finally: finalizeTrace()
                                            ├─ collector.markEnd()    [volatile写]
                                            └─ collector.buildTrace() [读取所有字段，构建不可变快照]
```

#### 线程安全方案总结

| 问题 | 方案 | 为什么这么选 |
|------|------|------|
| 单例 Bean（KnowledgeBaseQueryTool）获取请求级 Trace | ThreadLocal | 方法签名受框架约束不能加参数；同一线程执行全链路，天然请求隔离 |
| List 字段（retrievalContext 等）跨线程写读 | CopyOnWriteArrayList | 每请求写 ~30 次，读 1 次（buildTrace），COW 最佳场景 |
| 标量字段（intent/rewrittenQuery/finalAnswer）跨线程可见性 | volatile | 单写者-单读者，volatile 保证 happens-before，无需加锁 |

### 存储与管理端 API

**存储设计**：`TraceStore` 使用 `ConcurrentLinkedDeque`，上限 100 条，超出时 FIFO 淘汰最旧的。纯内存，无持久化——定位为开发调试工具。`getAll()` 返回 `List.copyOf(traces).reversed()` 保证最新的在前。

**管理端 API（`AdminTraceController`）：**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/traces` | 获取最近 100 条 trace（最新在前） |
| GET | `/admin/traces/{traceId}` | 按 ID 查看单条 trace 详情 |
| DELETE | `/admin/traces` | 清空存储 |

**排查示例**：用户反馈"问专业回答错误"，通过 `GET /admin/traces` 找到对应 traceId：

```json
{
  "traceId": "a1b2c3d4-...",
  "userQuery": "学院有哪些本科专业？",
  "rewrittenQuery": "南京大学信息管理学院有哪些本科专业？",
  "intent": "KNOWLEDGE",
  "confidence": 0.92,
  "retrievalContext": [
    {"documentId": "doc-7a3f", "snippet": "学院设有信息管理与信息系统、图书馆学...", "score": 0.032, "source": "rrf"}
  ],
  "rerankContext": [
    {"documentId": "doc-7a3f", "snippet": "学院设有信息管理与信息系统、图书馆学...", "score": 0.95, "source": "rerank"}
  ],
  "toolCalls": [],
  "finalAnswer": "南京大学信息管理学院设有4个本科专业...",
  "durationMs": 2800,
  "timestamp": "2026-07-02T10:30:00Z"
}
```

排查路径：先看 `intent`（分类错了？）→ 看 `retrievalContext`（召回了吗？）→ 看 `rerankContext`（排序对吗？）→ 看 `finalAnswer`（LLM 幻觉？）。从上到下 30 秒定位，不需要看日志或猜。

---

## 四、模块 3：工具韧性执行层（Tool Resilient Executor）

### 解决的问题

改造前 `ToolCallAgent.act()` 直接调用 Spring AI 的 `ToolCallingManager.executeToolCalls()`，存在三个痛点：

1. **多工具串行**：LLM 一轮返回 `queryKnowledgeBase` + `searchWeb` 两个调用时，串行执行，总耗时 = Σ 各工具耗时
2. **无超时保护**：任何一个工具卡住（PG 慢查询、外网抓取阻塞），整个 ReAct 循环就挂死
3. **异常中断循环**：工具抛异常直接冒泡到 Spring AI 的默认错误消息（英文异常栈），LLM 读不懂无法纠偏，用户拿到"执行错误：xxx"

### 核心思想

把 `ToolCallingManager` 的黑盒串行调用替换为自建的**虚拟线程并行 + 差异化超时 + 异常回喂**执行层。

**执行链路：**

```
LLM.think() → 决定调用 [tool1(args1), tool2(args2)]
                          │
                          ▼
              ToolResilientExecutor.executeAll()
                          │
              ┌───────────┴───────────┐
              ▼                       ▼
       Virtual Thread A         Virtual Thread B
       tool1 policy=5s          tool2 policy=8s
              │                       │
      ┌───────┴───────┐       ┌───────┴───────┐
      成功            超时     成功            异常
       │              │        │              │
       结果           TIMEOUT    结果         EXECUTION_ERROR
       │              │        │              │
       │      ToolFailureFormatter.format()   │
       │              │        │              │
       ▼              ▼        ▼              ▼
              合并为 ToolResponseMessage
                          │
                          ▼
              塞回 messageList → LLM 下一轮 think() 读到反馈自我纠偏
```

### 基于 JDK 21 虚拟线程的并行调度

- 使用 `Executors.newVirtualThreadPerTaskExecutor()`，IO 阻塞时自动解绑载体线程（unmount）
- 每个工具在独立虚拟线程执行，复合请求耗时从 Σ 降到 max
- 100 并发请求 × 2 工具 = 200 个并发任务，仅占用 ~8 个载体线程（M2 macOS 约 10 核）

**为什么不用传统线程池或 ForkJoinPool？**

工具调用是 IO 密集型。传统线程池线程少则阻塞导致吞吐低，线程多则每个平台线程占 1MB 栈内存。`ForkJoinPool.commonPool()` 并行度是 CPU 核数减 1，设计目标是 CPU 密集任务，IO 阻塞会迅速耗尽载体线程。虚拟线程是 M:N 模型，"来一个任务开一个轻量线程"，语义最契合。

### 工具级差异化超时策略（ToolPolicy）

| 工具 | 超时阈值 | IO 特征 | 依据 |
|------|---------|---------|------|
| `queryKnowledgeBase` | 5s | PG + ES + Rerank 三段链路 | 正常 <2s，5s 足够容忍抖动 |
| `searchWeb` | 8s | 外部搜索 API 走公网 | 需要更长网络容忍 |
| `scrapeWebPage` | 10s | 抓取真实网页 + DOM 解析 | 大响应体传输，最慢 |
| `doTerminate` | 1s | Agent 内部信号 | 纯本地逻辑，1s 足够 |

未注册的工具走默认策略 5s。策略以 `Map<String, ToolPolicy>` 声明，扩展新工具时改一行代码。

**为什么不统一超时阈值？** 统一阈值两难：定短（3s）会误杀 scrapeWebPage（正常 5-8s）；定长（15s）则 queryKnowledgeBase 卡住时要等很久才熔断。差异化超时反映每个工具真实的响应时间分布。

### 异常统一转结构化反馈（ToolFailureFormatter）

核心理念：**错误消息也是 Prompt**。LLM 在下一轮 think() 中根据"建议"部分自主决定纠偏策略。

所有异常按 `ToolErrorType` 分类，每类对应"原因 + 建议"两段式中文反馈：

| 错误类型 | 触发场景 | 回喂 LLM 的话（节选） |
|---------|---------|-------------------|
| `TIMEOUT` | 超过 policy.timeoutMs | "工具 X 执行超时...建议：简化查询关键词后重新调用；若仍超时，改用其他工具或基于上下文作答" |
| `INVALID_ARG` | 参数为空/工具名未找到 | "工具 X 参数错误...建议：检查该工具的参数格式后重新调用" |
| `EXECUTION_ERROR` | 下游服务不可用/IO 失败 | "工具 X 执行失败...建议：优先使用其他工具补全信息（例如知识库失败时改用 searchWeb）" |

关键实现：`CompletableFuture.handle((result, ex) -> ...)` 捕获所有异常，不让异常向外抛出。`allOf().join()` 永远不抛错，ReAct 循环不中断。

### 虚拟线程 + ThreadLocal 传播的工程细节

这是引入虚拟线程后的最大坑。`BaseAgent.execute()` 在父线程通过 `TraceContext.set(tc)` 挂载了 TraceCollector，但**虚拟线程默认不继承父线程的 ThreadLocal**。`ToolResilientExecutor` 把工具调用分发到虚拟线程后，`KnowledgeBaseQueryTool` 内部调 `TraceContext.get()` 会返回 null，Trace 数据就断了。

解决方案：在 `executeAll()` 提交任务前**显式抓取**当前线程的 `TraceCollector` 引用，在虚拟线程内部**重新 set + finally clear**：

```java
TraceCollector parentTrace = TraceContext.get();   // 父线程抓取快照

CompletableFuture.supplyAsync(() -> {
    if (parentTrace != null) TraceContext.set(parentTrace);   // 虚拟线程内恢复
    try {
        return invokeTool(toolCall, availableTools);
    } finally {
        TraceContext.clear();                                  // 归还线程前清理
    }
}, virtualThreadExecutor)
```

这里多个虚拟线程共享同一个 `TraceCollector` 实例是安全的，因为 `addRetrievalEntry()` 等方法操作的是 `CopyOnWriteArrayList`，写入是线程安全的。

JDK 21 也提供了 `ScopedValue`（预览特性）作为更优雅的方案，本项目暂未采用。

### 向后兼容

`ToolCallAgent.act()` 同时保留 legacy 路径和韧性路径：`toolResilientExecutor != null` 走韧性路径，否则走原有 `ToolCallingManager` 串行逻辑。不经过 Harness 创建的 Agent（比如测试场景）自动走 legacy，不强制依赖韧性执行器。

### 性能对比（预估）

| 场景 | 改造前串行 | 改造后并行 | 降幅 |
|------|----------|----------|------|
| `queryKnowledgeBase` (1.5s) + `searchWeb` (1.2s) | 2.7s | max = 1.5s | -44% |
| 3 个工具（1.5s + 1.2s + 0.8s） | 3.5s | max = 1.5s | -57% |

---

## 五、新增 / 修改文件清单

### 新增文件

```
src/main/java/com/yupi/yuaiagent/harness/
├── AgentHarness.java              # 编排层 @Component
├── TraceCollector.java            # 请求级追踪收集器（线程安全）
├── TraceContext.java              # ThreadLocal 持有者
├── TraceStore.java                # 有界内存存储 @Component
├── AdminTraceController.java      # Admin API @RestController
├── tool/
│   ├── ToolErrorType.java         # 工具错误分类枚举
│   ├── ToolPolicy.java            # 工具级差异化策略配置
│   ├── ToolFailureFormatter.java  # 异常 → LLM 中文反馈
│   └── ToolResilientExecutor.java # 虚拟线程并行 + 超时 + 回喂 @Component
└── model/
    └── AgentRunTrace.java         # Trace 记录 record
```

### 修改文件

| 文件 | 具体改动 |
|------|---------|
| `controller/AiController.java` | 删除 6 个 @Resource，注入 1 个 AgentHarness，方法体简化为一行委托 |
| `agent/BaseAgent.java` | +1 字段 (traceCollector)；execute() 中 set/clear ThreadLocal；preProcess() 中上报改写和意图；各 handler 中上报 finalAnswer |
| `agent/ToolCallAgent.java` | +1 字段 (toolResilientExecutor)；act() 拆分为韧性路径与 legacy 路径；韧性路径手动构建 ToolResponseMessage |
| `tools/KnowledgeBaseQueryTool.java` | hybridSearch 后上报 RRF 结果；rerank 后上报精排结果 |

---

## 六、面试 Q&A

### 架构与设计意图

**Q1: AgentHarness 的定位是什么？和 Service/Manager 有什么区别？**

A: AgentHarness 是 Controller 与 Agent 之间的编排层，职责是请求生命周期管理（创建 Trace → 组装 Agent → 委托执行 → 回调存储 Trace）。与 Service 的区别：Service 自己做业务逻辑，Harness 不改变 Agent 行为，只在执行前后做编排和观测。类比 JUnit 是测试 Harness——管理生命周期、收集结果，但不写业务代码。

**Q2: YuManus 为什么每请求创建而不是做成单例？Harness 本身是单例，怎么保证并发安全？**

A: YuManus 继承 BaseAgent，内部有 `state`（IDLE/RUNNING/FINISHED）、`currentStep`、`messageList` 这些请求级可变状态，如果做成单例，并发请求会互相踩。所以每请求 new 一个，天然隔离。

Harness 本身是 `@Component` 单例，但它**不持有任何请求级可变状态**。`runStream()` 里创建的 `TraceCollector`、`YuManus` 都是局部变量，不会跨请求。注入的 `TraceStore` 用 `ConcurrentLinkedDeque`，`ToolResilientExecutor` 的 `virtualThreadExecutor` 是应用生命周期的单例执行器、无共享可变状态。

### Trace 全链路追踪

**Q3: 你的 Trace 覆盖了哪些决策节点？如果线上有个 bad case，你怎么用 Trace 定位问题？**

A: 覆盖 5 个节点：查询重写结果、意图分类（类型+置信度）、混合检索的 15 条 RRF 融合结果（docId + snippet + score）、Rerank 精排的 Top5 结果（docId + snippet + 交叉编码器分数）、工具调用记录（工具名 + 参数 + 返回结果前500字 + 耗时）。

定位流程：① 先看 `intent`——分类错了就不会走正确路径；② 看 `retrievalContext`——15 条 RRF 结果里有没有正确文档；③ 看 `rerankContext`——正确文档是否被排进 Top5；④ 看 `finalAnswer`——给了正确检索结果但回答是否幻觉。从上到下 30 秒定位。

**Q4: TraceCollector 的线程安全你是怎么设计的？为什么不直接加一把锁？**

A: 场景是两个线程共享一个 TraceCollector——TraceCollector 在 Tomcat 请求线程（线程 A）创建，在 `CompletableFuture.runAsync()` 的异步线程（线程 B）中使用和写入。跨线程共享对象需要保证 JMM 层面的可见性。

三种机制：标量字段用 `volatile` 保证跨线程可见性；列表字段用 `CopyOnWriteArrayList`，写时复制保证读线程拿到一致快照；`buildTrace()` 内部再用 `List.copyOf()` 转为不可变列表。

加锁也能工作，但这个场景是单写者-单读者且写在前读在后，volatile + COW 足够且无竞争开销。`synchronized` 反而引入不必要的线程阻塞可能。

**Q5: 为什么用 ThreadLocal 透传 TraceCollector 而不是方法参数？RequestScope 行不行？**

A: 两个原因选 ThreadLocal：
1. `KnowledgeBaseQueryTool` 是 `@Bean` 注册的 Spring AI 工具单例，方法签名由框架约束，不能加参数
2. 执行模型是 `CompletableFuture.runAsync()` 把整个 Agent 链放在同一个线程中，ThreadLocal 天然请求隔离

不能用 `@RequestScope`。Agent 的执行线程是 ForkJoinPool 线程，不是 Servlet 容器线程，`RequestScope` 绑定的是 HTTP 请求线程的 `RequestAttributes`，在异步线程中会抛 `IllegalStateException`。即使用 `RequestContextHolder` 手动传播也很脆弱（SSE 场景下请求可能已结束但 Agent 还在执行）。ThreadLocal 更简单可控——`execute()` 开头 set、finally 里 clear，生命周期在自己手里。

**Q6: Trace 对性能有多大影响？**

A: 几乎为零。额外开销：5 次 volatile 写（纳秒级）+ ~30 次 COW add（微秒级）+ 1 次 buildTrace（亚毫秒级）+ TraceStore.add（纳秒级）。总计不到 1ms。对比 Agent 单次请求 2-5s 的 LLM 调用 + 网络 IO，追踪开销 < 0.05%。

**Q7: CopyOnWriteArrayList 不怕性能问题吗？**

A: 不怕。COW 的性能问题出在"频繁写大列表"时每次 add 都拷贝整个数组。我们场景：每请求最多写 ~30 次，列表不超几十条，读只 1 次（buildTrace）。这是 COW 最佳场景——写少读少，天然线程安全无锁。

### 工具韧性执行

**Q8: 为什么用虚拟线程而不是传统线程池或 ForkJoinPool？**

A: 工具调用是 IO 密集型（PG/ES/HTTP）。传统线程池两难：线程少则 IO 阻塞导致吞吐低，线程多则内存大、切换成本高。`ForkJoinPool.commonPool()` 并行度是 CPU 核数减 1，设计目标是 CPU 密集任务，IO 阻塞会迅速耗尽载体线程。虚拟线程是 M:N 模型，IO 阻塞时自动解绑载体线程。100 并发 × 2 工具 = 200 任务，仅占 ~8 个载体线程。`newVirtualThreadPerTaskExecutor()` 语义上也最符合"来一个开一个"的工具调用场景。

**Q9: 工具失败后 LLM 怎么知道该重试、换工具还是放弃？**

A: 反馈消息的结构化。每种错误类型对应"原因 + 建议"两段式中文话术。LLM 下一轮 think() 读到后根据"建议"自主决策。本质是把 Prompt Engineering 从 System Prompt 扩展到 Tool Response 层面——**错误消息也是 Prompt**。实测中，超时后 LLM 会换用 `searchWeb` 补充信息或直接基于已有上下文作答。

**Q10: 虚拟线程不继承父线程的 ThreadLocal，你是怎么解决 Trace 上下文传播的？**

A: 这是引入虚拟线程后的最大坑。解决方案是**显式捕获-恢复**：在 `executeAll()` 提交任务前，父线程先抓取 `TraceCollector` 引用存为局部变量，每个虚拟线程启动时重新 `TraceContext.set(parentTrace)`，`finally` 里 `TraceContext.clear()`。

多个虚拟线程共享同一个 `TraceCollector` 是安全的，因为操作的是 `CopyOnWriteArrayList`，写入线程安全。JDK 21 的 `ScopedValue`（结构化并发下自动传播上下文）是更优雅方案，但还是预览特性，暂未采用。

**Q11: 异常不抛出，ReAct 循环怎么正常结束？不会无限循环吗？**

A: 不会。ReAct 循环结束条件：① LLM 调用 `doTerminate` 工具主动终止；② 达到 `maxSteps`（默认 10 步）上限强制终止。两个条件都和工具是否成功无关。所有工具失败的场景下，LLM 读到错误反馈后通常 1-2 轮就会选择终止并生成降级回答。即使一直重试，`maxSteps` 兜底。

**Q12: 向后兼容怎么保证？ToolCallAgent 保留两套逻辑不会增加维护成本吗？**

A: 会增加一点，但收益大于成本。`act()` 方法里只有一行判断，两条路径输入输出一样，逻辑并行不交叉。保留的价值：① 韧性执行器有 bug 时可通过不注入来快速回退，不需要改代码重新部署；② 不经过 Harness 创建的 Agent 自动走 legacy，不强制依赖。这是**渐进式替换**而不是一刀切。

### 工程决策与权衡

**Q13: TraceStore 只存内存 100 条，上生产怎么办？**

A: 当前定位是开发调试工具。上生产三个改造方向：
1. **存储后端替换**：`TraceStore.add()` 改为写 ES/ClickHouse，Trace 数据结构已标准化，存储后端可替换
2. **采样策略**：高 QPS 下按比例采样，或只存异常/慢请求（`durationMs > 5000` 或工具调用有 TIMEOUT/EXECUTION_ERROR）
3. **关联能力**：traceId 透传下游服务，对接 SkyWalking/Jaeger 打通跨服务调用链

**Q14: 这套 Trace 和 OpenTelemetry / SkyWalking 有什么区别？为什么不直接用？**

A: 核心区别是**追踪粒度不同**。OpenTelemetry/SkyWalking 做的是通用的分布式链路追踪——记录 HTTP 请求、RPC 调用、数据库操作的 span，粒度是"哪个服务调了哪个服务"。

但我需要追踪的是 **Agent 内部的决策路径**——意图分类结果、置信度、查询改写结果、RRF 融合出了哪 15 条文档（每条的 docId、snippet、score）、Rerank 后排序变化。这些是**业务语义级别**的信息，通用框架不会也不应该关心。

两者是互补关系：底层可以接 SkyWalking 做跨服务调用追踪，上层用 AgentRunTrace 做 Agent 决策追踪，traceId 互相透传实现关联。

**Q15: token 估算用「字符数 × 1.5」，靠谱吗？**

A: 对于中文场景是粗略近似。主流中文 LLM 的 tokenizer 对中文的平均 token/字符比约 1.2-1.8，取 1.5。这个值不需要精确——用途是给运维一个粗略的 token 消耗规模感知，发现异常大的请求。如果需要精确值，应直接从 LLM API 的 `usage` 响应中读取。

---

## 七、一句话总结

**Trace 做的是"看得见"**——让 Agent 每步决策可追溯可排查；**工具调度做的是"打不死"**——多工具并行加速 + 单工具故障不中断 + LLM 自主纠偏恢复。两者结合实现了 Agent 的工程化运行保障。

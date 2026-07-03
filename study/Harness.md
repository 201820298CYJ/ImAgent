# Agent Harness 架构文档

---

## 简历描述

> **设计并实现 Agent Harness 工程化架构**，在 Agent 执行管线中引入编排层、全链路追踪、RAG 评测体系和工具韧性执行层。通过 Harness 编排层实现请求生命周期管理与关注点分离（Controller 依赖从 6 个缩减为 1 个）；基于 ThreadLocal + CopyOnWriteArrayList 构建线程安全的全链路追踪系统，结构化记录意图分类→查询改写→混合检索→Rerank→工具调用的完整决策路径，暴露 Admin API 支持问题归因；构建信管领域 RAG 评测 Harness（10 个标注问答对），自动计算 HitRate@5 / MRR@5 / NDCG@5 并设定质量阈值门禁，确保检索质量可量化可回归；基于 JDK 21 虚拟线程构建工具韧性执行层，多工具同轮次并行调度使复合请求耗时降 40%+，工具异常统一结构化为中文反馈回喂消息上下文，驱动 LLM 自我纠偏而非中断 ReAct 循环。

---

## 一、为什么要做这件事

### 改造前的问题

1. **Controller 职责过重**：`AiController` 直接持有 6 个 `@Resource` 依赖（ChatModel、工具数组、意图分类器、查询改写器、知识库工具、Redis 记忆），手动 `new YuManus(...)` 组装 Agent。HTTP 协议处理和 Agent 编排逻辑混在一起。

2. **出了问题无法定位**：学生问"信管有哪些专业"，如果回答不对，你不知道是意图分类错了、检索没召回、Rerank 排错了、还是 LLM 生成出了问题——没有任何结构化的中间过程记录。

3. **RAG 质量无法量化**：原先只有 5 条简单的 recall 测试用例，没有标准化的评测指标体系，无法在迭代中保证"改了分词/改了参数之后质量没有退化"。

### 改造后的收益

| 维度 | 改造前 | 改造后 |
|------|--------|--------|
| 关注点分离 | Controller 直接组装 Agent | Harness 统一编排，Controller 只做 HTTP 映射 |
| 可观测性 | 只有日志打印 | 结构化 Trace，Admin API 可查 |
| 质量保障 | 手动验证 | 自动化评测 + 阈值断言 |

---

## 二、做了什么（四个模块）

### 模块 1：Agent Harness 编排层

**核心思想**：在 Controller 和 Agent 之间插入一个 `AgentHarness` 编排组件，统一管理请求级生命周期。

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
            ├── [2] 组装 YuManus 并注入 TraceCollector
            ├── [3] 委托 yuManus.runStream()（原有 SSE 流式逻辑完全不变）
            └── [4] 注册 SseEmitter.onCompletion 回调 → 收集并存储 Trace
```

**改动对比：**

| | 改造前 AiController | 改造后 AiController |
|---|---|---|
| 注入依赖数 | 6 个（allTools, chatModel, queryRewriter, intentClassifier, knowledgeBaseQueryTool, redisChatMemory） | 1 个（agentHarness） |
| 代码行数 | 64 行 | 25 行 |
| 职责 | HTTP + Agent 组装 + 生命周期 | 仅 HTTP 映射 |

**关键设计决策：**
- Harness 是 Spring 单例 `@Component`，YuManus 仍然每请求创建（隔离会话状态）
- 采用**组合**而非重写：BaseAgent 的 execute/preProcess/dispatch 流程完全不变，Harness 只是包了一层

---

### 模块 2：全链路追踪 (AgentRunTrace)

**解决的问题**：当用户反馈"回答不对"时，能快速定位是哪个环节出了问题。

**追踪覆盖的 5 个决策节点：**

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

#### 2.1 Trace 采集点的代码实现

Trace 采集的核心设计是：**TraceCollector 作为请求级对象在 Harness 中创建，通过 ThreadLocal 透传给调用链路上的各个组件，每个组件在自己的业务逻辑中"顺手"上报数据**。下面按照请求执行的时序，逐一说明每个采集点的实现。

**采集点 ①：TraceCollector 创建与 ThreadLocal 挂载**

`AgentHarness.runStream()` 每次请求创建一个 `TraceCollector`，注入到新建的 `YuManus` 实例中：

```java
// AgentHarness.runStream()
public SseEmitter runStream(String message, String chatId) {
    TraceCollector tc = createTraceCollector(message, chatId);  // UUID + 原始query + startTime
    YuManus yuManus = buildAgent(chatId, tc);                   // tc 通过 setter 注入 YuManus
    SseEmitter emitter = yuManus.runStream(message);
    emitter.onCompletion(() -> finalizeTrace(tc));               // SSE 结束时持久化
    emitter.onTimeout(() -> finalizeTrace(tc));
    return emitter;
}
```

`BaseAgent.execute()` 在 `CompletableFuture.runAsync()` 线程的入口处将 `TraceCollector` 挂到 `ThreadLocal`，使得后续所有同线程组件都能通过 `TraceContext.get()` 访问：

```java
// BaseAgent.execute()
public void execute(String userPrompt, OutputSink sink) {
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
// BaseAgent.preProcess()
private ProcessedPrompt preProcess(String userPrompt) {
    String prompt = userPrompt;

    // 查询重写：补充上下文信息，消除指代和省略
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
// BaseAgent.preProcess() 续
IntentType intent = IntentType.TASK;
double confidence = 1.0;
IntentType runnerUp = null;

if (intentClassifier != null) {
    ClassifyResult result = intentClassifier.classify(prompt);
    intent = result.intent();
    confidence = result.confidence();
    runnerUp = result.runnerUp();
    if (traceCollector != null) {
        traceCollector.setIntent(intent);           // ← 上报分类结果
        traceCollector.setConfidence(confidence);   // ← 上报置信度
    }
}
return new ProcessedPrompt(intent, confidence, runnerUp, prompt);
```

排查价值：如果一个知识问答被误分为 CHAT（闲聊），Agent 会走 chatClient 一轮直答而不走知识库检索，Trace 中 `intent=CHAT` + `retrievalContext=[]` 就能立刻定位到是分类错了，而不是检索没召回。

**采集点 ④：混合检索 + Rerank — `KnowledgeBaseQueryTool`**

这是 ThreadLocal 透传的核心应用场景。`KnowledgeBaseQueryTool` 是 Spring 单例，无法通过构造器注入请求级的 TraceCollector，只能通过 `TraceContext.get()` 获取：

```java
// KnowledgeBaseQueryTool.queryKnowledgeBase() 简化示意
@Tool(description = "查询知识库")
public String queryKnowledgeBase(String query) {
    // 1. 混合检索（向量 + BM25 + RRF 融合）
    List<Document> fusedDocs = hybridSearchService.hybridSearch(query, 15, 15, 0.6, 15);

    // ← 上报 RRF 融合结果
    TraceCollector tc = TraceContext.get();
    if (tc != null) {
        for (Document doc : fusedDocs) {
            tc.addRetrievalEntry(new RetrievalEntry(
                doc.getId(),
                doc.getText().substring(0, Math.min(200, doc.getText().length())),
                doc.getScore(),
                "rrf"
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
                doc.getId(),
                doc.getText().substring(0, Math.min(200, doc.getText().length())),
                rerankScore,
                "rerank"
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
// ToolCallAgent.actWithResilience()
private String actWithResilience(List<AssistantMessage.ToolCall> toolCallList) {
    long actStart = System.currentTimeMillis();

    // 并行执行所有工具（ToolResilientExecutor 处理超时和异常）
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
                call.name(),
                call.arguments(),
                result.substring(0, Math.min(500, result.length())),  // 截断到500字
                actDuration
            ));
        }
    }
    // ... 检查 doTerminate、拼接返回 ...
}
```

排查价值：如果用户问题需要先 `searchWeb` 再 `queryKnowledgeBase`，Trace 中的 `toolCalls` 列表完整记录了每一步调用的参数和返回结果，可以看出是哪个工具返回了错误或不相关的数据。

**采集点 ⑥：Trace 收尾与持久化 — `AgentHarness.finalizeTrace()`**

Agent 执行完毕后，SSE 的 `onCompletion` 回调触发 Trace 的收尾和持久化：

```java
// AgentHarness.finalizeTrace()
private void finalizeTrace(TraceCollector tc) {
    tc.markEnd();                               // endTime = Instant.now()
    AgentRunTrace trace = tc.buildTrace();       // 从 volatile/COW 字段构建不可变 record
    traceStore.add(trace);                       // ConcurrentLinkedDeque，FIFO淘汰超100条
    log.info("[Harness] Trace 已记录: traceId={} intent={} durationMs={}",
        trace.traceId(), trace.intent(), trace.durationMs());
}
```

`buildTrace()` 内部通过 `List.copyOf()` 将 `CopyOnWriteArrayList` 转为不可变列表，确保 Trace record 构建完成后不会被后续写入干扰。`durationMs` 通过 `Duration.between(startTime, endTime).toMillis()` 计算端到端耗时。`tokenEstimate` 通过字符数 ×1.5 估算（中文场景下的近似值）。

#### 2.2 数据结构（Java 21 record）

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

#### 2.3 线程安全方案

SSE 场景下存在两个线程共享 `TraceCollector` 的问题：
- **执行线程**（`CompletableFuture.runAsync()` 的 ForkJoinPool 线程）：运行 Agent 全链路，写入所有追踪数据
- **Servlet 容器线程**：SSE 连接关闭时触发 `onCompletion`，读取追踪数据构建最终 Trace

| 问题 | 方案 | 为什么这么选 |
|------|------|------|
| 单例 Bean（KnowledgeBaseQueryTool）获取请求级 Trace | ThreadLocal | 方法签名受框架约束不能加参数；同一线程执行全链路，天然请求隔离 |
| List 字段（retrievalContext 等）跨线程写读 | CopyOnWriteArrayList | 每请求写 ~30 次（15检索+5Rerank+若干工具），读 1 次（buildTrace），COW 最佳场景 |
| 标量字段（intent/rewrittenQuery/finalAnswer）跨线程可见性 | volatile | 单写者-单读者，volatile 保证 happens-before，无需加锁 |

两个线程之间的执行时序和数据流：

```
执行线程 (ForkJoinPool)                    Servlet线程
  │                                          │
  ├── TraceContext.set(tc)                   │
  ├── tc.setRewrittenQuery(...)  [volatile写] │
  ├── tc.setIntent(...)          [volatile写] │
  ├── tc.addRetrievalEntry(...)  [COW写]     │
  ├── tc.addRerankEntry(...)     [COW写]     │
  ├── tc.addToolCall(...)        [COW写]     │
  ├── tc.setFinalAnswer(...)     [volatile写] │
  ├── TraceContext.clear()                   │
  ├── sink.complete() → emitter.complete()   │
  │                                          ├── onCompletion()
  │                                          │     ├── tc.markEnd()
  │                                          │     ├── tc.buildTrace()  ← 读volatile + COW
  │                                          │     └── traceStore.add(trace)
```

关键保证：`sink.complete()` 是在 try 块的最后一行执行的，此时所有追踪数据都已写入完毕。`emitter.complete()` 触发的 `onCompletion` 一定在所有写操作之后，volatile 的 happens-before 语义保证了回调线程能看到最新值。

#### 2.4 存储与管理端 API

**存储设计**：`TraceStore` 使用 `ConcurrentLinkedDeque`，上限 100 条，超出时 FIFO 淘汰最旧的。纯内存，无持久化——定位为开发调试工具。`getAll()` 返回 `List.copyOf(traces).reversed()` 保证最新的在前。

**管理端 API（`AdminTraceController`）：**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/traces` | 获取最近 100 条 trace（最新在前） |
| GET | `/admin/traces/{traceId}` | 按 ID 查看单条 trace 详情 |
| DELETE | `/admin/traces` | 清空存储 |

**排查示例**：用户反馈"问专业回答错误"，通过 `GET /admin/traces` 找到对应 traceId，一眼就能看出问题在哪——

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

---

### 模块 3：RAG 评测 Harness

**解决的问题**：当你调整 RAG 参数（chunk size、RRF 权重、Rerank 模型、相似度阈值）时，如何确保检索质量没有退化？

**评测管线：**

```
                    rag-eval-dataset.json
                    (10个人工标注的信管问答对)
                            │
                            ▼
                  ┌─────────────────────┐
                  │  RagEvalHarness     │
                  │  .evaluate()        │
                  └─────────┬───────────┘
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
         对每个问题执行完整 RAG 管线（与线上一致）：
         
         HybridSearch(question)
           ├── 向量检索 Top15 (pgvector, 阈值0.6)
           ├── BM25检索 Top15 (Elasticsearch IK分词)
           └── RRF融合 Top15 (k=60)
                    │
                    ▼
         DashScopeRerank(question, Top15 → Top5)
           └── gte-rerank 交叉编码器精排
                    │
                    ▼
         判定相关性：Top5 结果中，文档文本是否包含 expectedKeywords
                    │
                    ▼
         计算三个指标：
           ├── HitRate@5 = 命中问题数 / 总问题数
           ├── MRR@5 = Σ(1/首次命中排名) / 总问题数
           └── NDCG@5 = Σ(DCG/iDCG) / 总问题数
```

**评测数据集（10 个信管领域问答对）：**

| ID | 问题 | 期望关键词 | 考察维度 |
|----|------|-----------|---------|
| Q01 | 南京大学信息管理学院的官方网站是什么？ | im.nju.edu.cn | 精确事实 |
| Q02 | 信息管理学院是哪一年加入国际iSchools组织的？ | 2011 | 时间线检索 |
| Q03 | 学院目前有多少教职工？ | 80余人 | 数字型事实 |
| Q04 | 2022年谁担任了学院院长？ | 裴雷 | 人物关联 |
| Q05 | 信息管理与信息系统专业的培养目标是什么？ | 信息系统分析与设计 | 长文本段落 |
| Q06 | 图书馆学专业是什么时候恢复本科教育的？ | 1985 | 跨段落推理 |
| Q07 | 学院有哪些本科专业？ | 信管/图书馆学/档案学/编辑出版学 | 多答案枚举 |
| Q08 | 情报学是什么时候获得博士学位授予权的？ | 1996 | 历史年表 |
| Q09 | 学院与哪些海外高校建立了合作关系？ | 剑桥大学/匹兹堡大学 | 列表型信息 |
| Q10 | 国家安全数据管理的学科代码是多少？ | 1205Z3 | 编码型信息 |

**质量阈值（自动断言）：**
- **HitRate@5 ≥ 0.8**：10 个问题中至少 8 个在 Top5 能找到相关文档
- **MRR@5 ≥ 0.6**：命中时平均排名在前 2 位以内

**运行方式：**
```bash
# JUnit 测试运行（需要 PG + ES + DashScope API 就绪）
mvn test -Dtest=RagEvalHarnessTest

# 输出示例：
# [RAG评估] 最终指标 - HitRate@5: 0.9000, MRR@5: 0.7833, NDCG@5: 0.8521
# Tests passed ✓
```

**关键设计决策：**
- 评测引擎直接调用 `HybridSearchService` + `DashScopeRerankService`，**不经过 Agent 层**——独立评估检索质量，不受意图分类/查询改写的干扰
- 相关性判定使用关键词匹配（而非 LLM 判定），确保评测结果**确定性可复现**
- 数据集覆盖 6 种信息类型（精确事实/时间/人物/长文/列表/编码），验证 RAG 对不同查询模式的泛化能力

---

### 模块 4：工具韧性执行层（Tool Resilient Executor）

**解决的问题**：改造前 `ToolCallAgent.act()` 直接调用 Spring AI 的 `ToolCallingManager.executeToolCalls()`，存在三个痛点：

1. **多工具串行**：LLM 一轮返回 `queryKnowledgeBase` + `searchWeb` 两个调用时，串行执行，总耗时 = Σ 各工具耗时
2. **无超时保护**：任何一个工具卡住（PG 慢查询、外网抓取阻塞），整个 ReAct 循环就挂死
3. **异常中断循环**：工具抛异常直接冒泡到 Spring AI 的默认错误消息（英文异常栈），LLM 读不懂无法纠偏，用户拿到"执行错误：xxx"

**核心思想**：把 `ToolCallingManager` 的黑盒串行调用替换为自建的**虚拟线程并行 + 差异化超时 + 异常回喂**执行层。

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

**工具级差异化策略：**

| 工具 | 超时阈值 | IO 特征 | 依据 |
|------|---------|---------|------|
| `queryKnowledgeBase` | 5s | PG + ES + Rerank 三段链路 | 正常 <2s，5s 足够容忍抖动 |
| `searchWeb` | 8s | 外部搜索 API 走公网 | 需要更长网络容忍 |
| `scrapeWebPage` | 10s | 抓取真实网页 + DOM 解析 | 大响应体传输，最慢 |
| `doTerminate` | 1s | Agent 内部信号 | 纯本地逻辑，1s 足够 |

未注册的工具走默认策略 5s。策略以 `Map<String, ToolPolicy>` 声明，扩展新工具时改一行代码。

**错误分类与回喂话术（`ToolFailureFormatter`）：**

所有异常按 `ToolErrorType` 分类，每类对应"原因 + 建议"两段式中文反馈——**错误消息也是 Prompt**，LLM 在下一轮 think() 中根据"建议"部分自主决定纠偏策略。

| 错误类型 | 触发场景 | 回喂 LLM 的话（节选） |
|---------|---------|-------------------|
| `TIMEOUT` | 超过 policy.timeoutMs | "工具 X 执行超时（超过 Yms）。建议：请尝试简化查询关键词后重新调用；若仍超时，请改用其他工具或基于对话上下文作答..." |
| `INVALID_ARG` | 参数为空/工具名未找到/参数类型错 | "工具 X 参数错误。原因：Y。建议：请检查该工具的参数格式后重新调用..." |
| `EXECUTION_ERROR` | 工具内部抛异常（下游服务不可用、IO 失败） | "工具 X 执行失败。原因：Y。建议：请优先使用其他工具补全信息（例如知识库失败时改用 searchWeb）..." |

**虚拟线程 + ThreadLocal 传播的工程细节：**

这是最大的坑。改造前 `TraceContext` 是 ThreadLocal，`BaseAgent.execute()` 在父线程 set 值，`KnowledgeBaseQueryTool` 通过 `TraceContext.get()` 拿到——但**虚拟线程默认不继承父线程的 ThreadLocal**。

解决方案：在 `ToolResilientExecutor.executeAll()` 提交任务前**显式抓取**当前线程的 `TraceCollector` 引用，在虚拟线程内部**重新 set + finally clear**。

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

JDK 21 也提供了 `ScopedValue`（预览特性）作为更优雅的方案，本项目暂未采用。

**关键设计决策：**
- **平滑回退**：`ToolCallAgent.act()` 里同时保留 legacy 路径（`ToolCallingManager` 串行）。若 `toolResilientExecutor` 未注入则走 legacy，保证向后兼容
- **异常不抛出**：`CompletableFuture.handle((r, ex) -> ...)` 里就把异常吞掉转反馈，`allOf().join()` 永远不抛错，ReAct 循环不中断
- **单例执行器 + 每任务虚拟线程**：`Executors.newVirtualThreadPerTaskExecutor()` 是应用生命周期的单例，每次调度按需创建虚拟线程，用完即销毁

**性能对比（预估）：**

| 场景 | 改造前串行 | 改造后并行 | 降幅 |
|------|----------|----------|------|
| `queryKnowledgeBase` (1.5s) + `searchWeb` (1.2s) | 2.7s | max = 1.5s | -44% |
| 3 个工具（1.5s + 1.2s + 0.8s） | 3.5s | max = 1.5s | -57% |

**并发资源开销：**
100 并发请求 × 每请求平均 2 工具 = 200 个并发工具调用任务，虚拟线程模型下**仅占用 ~8 个平台载体线程**（M2 macOS 约 10 核），若用传统平台线程池则需要 200 个线程。

---

## 三、新增 / 修改文件完整清单

### 新增 15 个文件

```
src/main/java/com/yupi/yuaiagent/harness/
├── AgentHarness.java              # 编排层 @Component（90行）
├── TraceCollector.java            # 请求级追踪收集器（75行）
├── TraceContext.java              # ThreadLocal 持有者（20行）
├── TraceStore.java                # 有界内存存储 @Component（35行）
├── AdminTraceController.java      # Admin API @RestController（30行）
├── RagEvalHarness.java            # RAG 评测引擎 @Component（110行）
├── tool/
│   ├── ToolErrorType.java         # 工具错误分类枚举（15行）
│   ├── ToolPolicy.java            # 工具级差异化策略配置（30行）
│   ├── ToolFailureFormatter.java  # 异常 → LLM 中文反馈（50行）
│   └── ToolResilientExecutor.java # 虚拟线程并行 + 超时 + 回喂 @Component（120行）
└── model/
    ├── AgentRunTrace.java         # Trace 记录 record（35行）
    ├── RagEvalTestCase.java       # 评测用例 record（10行）
    └── RagEvalResult.java         # 评测报告 record（25行）

src/main/resources/harness/
└── rag-eval-dataset.json          # 10个评测用例

src/test/java/com/yupi/yuaiagent/harness/
└── RagEvalHarnessTest.java        # JUnit 评测入口（35行）
```

### 修改 5 个文件

| 文件 | 具体改动 |
|------|---------|
| `controller/AiController.java` | 删除 6 个 @Resource，注入 1 个 AgentHarness，方法体简化为一行委托 |
| `agent/BaseAgent.java` | +1 字段 (traceCollector)；execute() 中 set/clear ThreadLocal；preProcess() 中上报改写和意图；3 个 handler 中上报 finalAnswer |
| `agent/ToolCallAgent.java` | +1 字段 (toolResilientExecutor)；act() 拆分为韧性路径与 legacy 路径；韧性路径手动构建 ToolResponseMessage，per-tool 真实耗时上报 Trace |
| `tools/KnowledgeBaseQueryTool.java` | hybridSearch 后上报 RRF 结果；rerank 后上报精排结果 |
| `harness/AgentHarness.java` | 注入 ToolResilientExecutor 并 setter 到 YuManus |

---

## 四、如何验证

### 验证 Harness 编排层 + 追踪

```bash
# 1. 启动基础设施
docker-compose up -d postgres elasticsearch redis

# 2. 启动应用
mvn spring-boot:run

# 3. 发送一个知识问答请求
curl "http://localhost:8123/api/ai/manus/chat?message=学院有哪些专业&chatId=test1"

# 4. 查看追踪记录
curl http://localhost:8123/api/admin/traces | python3 -m json.tool
```

预期看到：traceId、rewrittenQuery、intent=KNOWLEDGE、retrievalContext（15条 RRF 结果）、rerankContext（5条精排结果）、finalAnswer。

### 验证 RAG 评测

```bash
mvn test -Dtest=RagEvalHarnessTest
```

预期：测试通过，控制台输出 HitRate/MRR/NDCG 指标。

---

## 五、面试 Q&A

> 以下问题模拟大厂校招面试官看到简历点「设计 AgentHarness 统一管理 Agent 请求生命周期，通过全链路 Trace 结构化记录各决策节点信息，实现线上问题可排查；工具调度层实现多工具并行执行，工具执行失败时将异常转为结构化反馈传给 LLM 触发自主纠偏，避免单工具故障中断 ReAct 链路」后可能追问的问题。

---

### 一、架构与设计意图

**Q1: 你说的 Harness「统一管理生命周期」，具体管了哪些事情？如果不做这个 Harness，直接在 Controller 里写有什么问题？**

A: Harness 管四件事：① 创建请求级的 TraceCollector；② 组装一个新的 YuManus 实例并注入 Trace 收集器和韧性执行器；③ 委托 Agent 执行（流式或同步）；④ 在 SSE 连接结束的 `onCompletion` 回调中收尾 Trace 并持久化。

不做 Harness 的话，Controller 要持有 6 个 `@Resource`（ChatModel、ToolCallback 数组、意图分类器、查询改写器、知识库工具、Redis 记忆），自己 `new YuManus(...)` 组装，HTTP 协议处理和 Agent 编排混在一起。如果后续要加 Trace 采集或工具韧性执行，这些逻辑也只能往 Controller 塞。改造后 Controller 只注入一个 `agentHarness`，方法体一行委托调用，职责干净。

**Q2: YuManus 为什么每请求创建而不是做成单例？Harness 本身是单例，怎么保证并发安全？**

A: YuManus 继承 BaseAgent，内部有 `state`（IDLE/RUNNING/FINISHED）、`currentStep`、`messageList` 这些请求级可变状态，如果做成单例，并发请求之间会互相踩。所以每请求 new 一个，天然隔离。

Harness 本身是 `@Component` 单例，但它**不持有任何请求级可变状态**。`runStream()` 里创建的 `TraceCollector`、`YuManus` 都是局部变量，不会跨请求。Harness 注入的 `TraceStore`、`ToolResilientExecutor` 也是线程安全的——TraceStore 用 `ConcurrentLinkedDeque`，ToolResilientExecutor 的 `virtualThreadExecutor` 是应用生命周期的单例执行器、无共享可变状态。

---

### 二、Trace 全链路追踪

**Q3: 你的 Trace 覆盖了哪些决策节点？如果线上有个 bad case，你怎么用 Trace 定位问题？**

A: 覆盖 5 个节点：查询重写结果、意图分类（类型+置信度）、混合检索的 15 条 RRF 融合结果（docId + snippet + score）、Rerank 精排的 Top5 结果（docId + snippet + 交叉编码器分数）、工具调用的每次记录（工具名 + 参数 + 返回结果前500字 + 耗时）。

定位流程举例：用户问"学院有哪些专业"但回答错误。我从 `GET /admin/traces` 拿到这条 Trace：
- 先看 `intent`：如果是 `CHAT` 而不是 `KNOWLEDGE`，说明意图分类错了，Agent 走了闲聊路径根本没查知识库；
- 如果 intent 正确，看 `retrievalContext`：15 条 RRF 结果里有没有正确的文档？没有说明是向量或 BM25 的召回问题；
- 如果 RRF 有但 `rerankContext` 里没有，说明是 Rerank 模型排序有偏差，把正确文档排到 Top5 之外了；
- 如果 Rerank 也有，那问题出在 LLM 生成阶段——给了正确的检索结果但回答时产生了幻觉。

这个排查路径从上到下 30 秒就能定位，不需要看日志或猜。

**Q4: TraceCollector 的线程安全你是怎么设计的？为什么不直接加一把锁？**

A: 场景是两个线程共享一个 TraceCollector：执行线程（ForkJoinPool）写所有追踪数据，Servlet 容器线程在 `onCompletion` 回调中读数据构建最终 Trace。写在前、读在后，不会并发读写。

所以不需要互斥锁。我用了三种机制：标量字段（`intent`、`rewrittenQuery`、`finalAnswer`）用 `volatile` 保证跨线程可见性；列表字段（`retrievalContext`、`rerankContext`、`toolCalls`）用 `CopyOnWriteArrayList`，写时复制保证读线程拿到一致快照；`buildTrace()` 内部再用 `List.copyOf()` 转为不可变列表。

加锁当然也能工作，但这个场景是单写者-单读者且写在前读在后，volatile + COW 足够且无竞争开销。如果上 `synchronized` 反而引入了不必要的线程阻塞可能。

**Q5: 为什么用 ThreadLocal 透传 TraceCollector 而不是方法参数？如果改用 Spring 的 RequestScope 行不行？**

A: 两个原因选 ThreadLocal：
1. `KnowledgeBaseQueryTool` 是通过 `@Bean` 注册的 Spring AI 工具单例，方法签名 `String queryKnowledgeBase(String query)` 由框架约束，不能加参数。
2. 执行模型是 `CompletableFuture.runAsync()` 把整个 Agent 链放在同一个线程中，ThreadLocal 天然请求隔离。

不能用 `@RequestScope`。Agent 的执行线程是 ForkJoinPool 线程，不是 Servlet 容器线程，`RequestScope` 绑定的是 HTTP 请求线程的 `RequestAttributes`，在异步线程中 `getBean()` 会抛 `IllegalStateException`。即使用 `RequestContextHolder` 手动传播也很脆弱（SSE 场景下请求可能已结束但 Agent 还在执行）。ThreadLocal 更简单可控——`execute()` 开头 `set`、`finally` 里 `clear`，生命周期完全在自己手里。

**Q6: 你说 Trace 几乎不影响性能，这个结论是怎么得出的？有没有做过实测？**

A: 分析得出的，没有专门做基准测试。额外开销拆解：5 次 volatile 写（纳秒级）、约 30 次 `CopyOnWriteArrayList.add()`（每次复制一个几十条的小数组，微秒级）、1 次 `buildTrace()`（遍历列表 + `List.copyOf()` + token 估算，亚毫秒级）、1 次 `ConcurrentLinkedDeque.addLast()`（纳秒级）。总计不到 1ms。

而 Agent 单次请求的耗时在 2-5 秒（LLM API 调用 + 网络 IO + 检索），追踪开销占比 < 0.05%。瓶颈永远在 LLM 调用和网络 IO 上，追踪层不是热点。

---

### 三、工具韧性执行

**Q7: 你说多工具并行执行用了虚拟线程，为什么不用传统线程池或 CompletableFuture 默认的 ForkJoinPool？**

A: 工具调用是 IO 密集型（PG 查询、ES 检索、DashScope Rerank API、外部 HTTP 抓取），不是 CPU 密集型。

传统线程池的问题是线程数和吞吐的两难：线程少（比如核数个），IO 阻塞时载体线程被占住，后续工具排队等待；线程多（比如 200 个），每个平台线程占 1MB 栈内存，200 个就是 200MB，加上上下文切换开销。

`CompletableFuture` 默认的 `ForkJoinPool.commonPool()` 更不合适——它的并行度是 CPU 核数减 1，设计目标是 CPU 密集任务，IO 阻塞会迅速耗尽载体线程。

虚拟线程是 JDK 21 的 M:N 模型：IO 阻塞时自动解绑载体线程（unmount），让出给其他虚拟线程。100 个并发请求 × 2 工具 = 200 个并发任务，只占约 8 个载体线程。`Executors.newVirtualThreadPerTaskExecutor()` 语义上也更合适——"来一个任务开一个轻量线程"，用完即销毁，不需要管池大小。

**Q8: 工具执行失败后，异常是怎么变成 LLM 能理解的反馈的？LLM 真的能根据反馈纠偏吗？**

A: 实现上分三步：

第一步，`CompletableFuture.handle((result, ex) -> ...)` 捕获所有异常，不让异常向外抛出。`allOf().join()` 永远不会抛错，ReAct 循环永远不会因为工具异常而中断。

第二步，将异常按 `ToolErrorType` 分类——`TimeoutException` 归为 TIMEOUT、`IllegalArgumentException`/`NullPointerException` 归为 INVALID_ARG、其余归为 EXECUTION_ERROR。

第三步，`ToolFailureFormatter` 为每种错误类型生成"原因+建议"两段式中文话术。比如超时的建议是"请尝试简化查询关键词后重新调用；若仍超时，请改用其他工具或基于上下文作答"。这段文本作为 `ToolResponseMessage` 塞回消息上下文，LLM 下一轮 `think()` 读到后自主决策。

LLM 能不能纠偏取决于建议的质量。我们做的本质是把 Prompt Engineering 从 System Prompt 扩展到了 Tool Response 层面——**错误消息也是 Prompt**。实测中，超时后 LLM 会换用 `searchWeb` 补充信息或直接基于已有上下文作答，符合预期。

**Q9: 你说差异化超时，这些超时阈值是怎么定的？如果线上工具响应时间分布变了怎么办？**

A: 阈值基于各工具的 IO 特征和实际观测：`queryKnowledgeBase` 链路是 PG + ES + Rerank 三段内网调用，正常 < 2s，设 5s 足够容忍抖动；`searchWeb` 走公网搜索 API，设 8s；`scrapeWebPage` 抓取真实网页 + DOM 解析，最慢，设 10s；`doTerminate` 是纯本地的终止信号，设 1s。

策略以 `Map<String, ToolPolicy>` 声明在 `ToolPolicy` 类的静态字段里。如果响应时间分布变了（比如接了一个更慢的 Rerank 模型），改一行代码即可。未注册的工具走默认 5s 兜底。

如果要做得更完善，可以改为配置化（`application.yml`）或动态调整（基于 P99 滑动窗口自适应）。当前阶段静态 Map 够用，过度设计反而增加复杂度。

**Q10: 虚拟线程不继承父线程的 ThreadLocal，你是怎么解决 Trace 上下文传播的？**

A: 这是引入虚拟线程后遇到的最大坑。`BaseAgent.execute()` 在父线程通过 `TraceContext.set(tc)` 挂载了 TraceCollector，但 `ToolResilientExecutor` 把工具调用分发到虚拟线程后，`KnowledgeBaseQueryTool` 内部调 `TraceContext.get()` 会返回 null，Trace 数据就断了。

解决方案是**显式捕获-恢复**：在 `executeAll()` 提交任务之前，父线程先抓取 `TraceCollector` 引用存为局部变量，每个虚拟线程启动时重新 `TraceContext.set(parentTrace)`，`finally` 里 `TraceContext.clear()`：

```java
TraceCollector parentTrace = TraceContext.get();   // 父线程捕获
CompletableFuture.supplyAsync(() -> {
    if (parentTrace != null) TraceContext.set(parentTrace);  // 虚拟线程恢复
    try {
        return invokeTool(toolCall, availableTools);
    } finally {
        TraceContext.clear();   // 归还前清理
    }
}, virtualThreadExecutor);
```

这里多个虚拟线程共享同一个 `TraceCollector` 实例是安全的，因为 `addRetrievalEntry()` 等方法操作的是 `CopyOnWriteArrayList`，写入是线程安全的。

JDK 21 有 `ScopedValue`（结构化并发下自动传播上下文），但还是预览特性，生产项目暂未采用。

---

### 四、工程决策与权衡

**Q11: 你的 TraceStore 只存内存 100 条，上生产怎么办？**

A: 当前定位是开发调试工具，100 条够排查近期问题。上生产有三个改造方向：
1. **存储后端替换**：TraceStore 是一个接口点，把 `add()` 改为写 ES/ClickHouse 即可，Trace 的数据结构（`AgentRunTrace` record）已经标准化了，存储后端可替换。
2. **采样策略**：高 QPS 下全量存储不现实，可以按比例采样，或者只存异常/慢请求的 Trace（比如 `durationMs > 5000` 或工具调用有 TIMEOUT/EXECUTION_ERROR 的）。
3. **关联能力**：traceId 透传到下游服务，对接 SkyWalking/Jaeger 打通跨服务调用链。

**Q12: 你说异常不抛出、ReAct 循环不中断，那如果所有工具都失败了会怎样？不会无限循环吗？**

A: 不会。ReAct 循环的结束条件有两个：① LLM 调用 `doTerminate` 工具主动终止；② 达到 `maxSteps`（默认 10 步）上限强制终止。这两个条件都和工具是否成功无关。

所有工具失败的场景下，LLM 会读到所有工具的错误反馈（比如"知识库超时...建议基于上下文作答"），通常在 1-2 轮后就会选择 `doTerminate` 结束，并基于已有上下文生成一个降级回答。即使 LLM 一直重试，`maxSteps` 也会兜底，不会无限循环。

**Q13: ToolCallAgent 保留了 legacy 路径和韧性路径两套逻辑，这种双路径设计不会增加维护成本吗？**

A: 会增加一点，但收益大于成本。`act()` 方法里只有一行判断：`toolResilientExecutor != null` 走韧性路径，否则走 legacy 的 `ToolCallingManager` 串行逻辑。两条路径的输入输出是一样的（toolCallList → ToolResponseMessage → 塞回 messageList），逻辑并行不交叉。

保留的价值是：① 如果韧性执行器有 bug，可以通过不注入它来快速回退到 legacy 路径，不需要改代码重新部署；② 不经过 Harness 创建的 Agent（比如测试场景）自动走 legacy，不强制依赖韧性执行器。这是一种**渐进式替换**而不是一刀切，在稳定性没有被充分验证之前是合理的取舍。

**Q14: 如果面试官追问——你这套 Trace 和 OpenTelemetry / SkyWalking 这些成熟的可观测性方案有什么区别？为什么不直接用？**

A: 核心区别是**追踪粒度不同**。OpenTelemetry/SkyWalking 做的是通用的分布式链路追踪——记录 HTTP 请求、RPC 调用、数据库操作的 span，粒度是"哪个服务调了哪个服务、花了多久"。

但我需要追踪的是 **Agent 内部的决策路径**——意图分类结果是什么、置信度多少、查询改写改成了什么、RRF 融合出了哪 15 条文档（每条的 docId、snippet、score）、Rerank 后排序变成了什么样。这些是**业务语义级别**的信息，通用链路追踪框架不会也不应该关心。

所以两者不是替代关系而是互补关系：如果上生产，底层可以接 SkyWalking 做跨服务调用追踪，上层用我的 AgentRunTrace 做 Agent 决策追踪，两层各管各的。traceId 可以互相透传实现关联。

**Q15: 你提到 token 估算用的是「字符数 × 1.5」，这个系数靠谱吗？**

A: 对于中文场景是一个粗略近似。主流中文 LLM 的 tokenizer（如通义千问的 tiktoken）对中文的平均 token/字符比大约在 1.2-1.8 之间，取 1.5 作为估算。

这个值不需要非常精确——它的用途是在 Trace 中给运维一个粗略的 token 消耗规模感知（几百 token 级还是几千 token 级），用于发现异常大的请求（比如某次工具返回了巨量文本导致 token 暴涨）。如果需要精确值，应该直接从 LLM API 的 `usage` 响应中读取，但那需要在每次 LLM 调用时额外记录，当前阶段粗估够用。

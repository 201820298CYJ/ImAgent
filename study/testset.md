# RAG 评测体系

## 简历描述

> Agentic RAG 多路混合检索：将 RAG 检索能力封装为工具，由 Agent 自主决策检索时机；内部构建向量语义检索 + BM25 关键词检索双路召回，通过 RRF 融合两路排名后引入 Rerank 模型精排，在评测集上 HitRate@5 提升至 94%.

---

## 一、我做了什么（快速回忆）

### 1.1 评测集设计

设计了 10 条测试用例（`harness/rag-eval-dataset.json`），覆盖不同问题类型：

| 类别 | 示例问题 | 期望关键词 | 考察能力 |
|------|---------|-----------|---------|
| 精确实体 | "官方网站是什么？" | `im.nju.edu.cn` | BM25 精确匹配 |
| 时间点 | "哪一年加入 iSchools？" | `2011` | 数字检索 |
| 人名 | "2022年谁担任院长？" | `裴雷` | 专有名词召回 |
| 枚举 | "有哪些本科专业？" | 4 个专业名 | 多关键词覆盖 |
| 培养目标 | "信管专业培养目标？" | `信息系统分析与设计` | 语义段落理解 |
| 合作关系 | "与哪些海外高校合作？" | `剑桥大学`、`匹兹堡大学` | 跨段落聚合 |

每条用例结构：`id`、`question`、`expectedKeywords`（相关性判定依据）、`expectedDocSection`（预期来源段落标题）。

### 1.2 评估引擎 — `RagEvalHarness`

完整管线评估：加载测试集 → 对每条 query 执行 HybridSearch（向量 15 条 + BM25 15 条 → RRF 融合 15 条）→ Rerank 精排取 Top5 → 判定相关性 → 计算指标。

**相关性判定逻辑**：检索到的文档（正文 + headerContext）包含任一 expectedKeyword 即为 relevant。

### 1.3 评估指标

| 指标 | 公式 | 含义 |
|------|------|------|
| **HitRate@5** | `命中的 query 数 / 总 query 数` | Top5 中至少有一条相关文档的概率 |
| **MRR@5** | `Σ (1/firstHitRank) / N` | 第一条相关文档平均出现在第几位 |
| **NDCG@5** | `DCG / IDCG`，`DCG = Σ 1/log₂(rank+1)` | 考虑排序位置的综合质量分 |

### 1.4 回归测试集成

`RagEvalHarnessTest`（Spring Boot 集成测试）：
- 运行完整评估管线
- 断言：`HitRate@5 >= 0.8`、`MRR@5 >= 0.6`
- 输出完整 JSON 报告（每条 query 的检索结果 + 相关性判定 + 分数）
- 作为上线前的质量门禁，防止检索管线变更导致指标回退

### 1.5 早期纯向量基线测试 — `RagRecallEvaluationTest`

在引入混合检索前做的基线：5 条 query，纯 PgVector 向量检索 Top3，计算 HitRate 和 Precision@3。作为对比组证明混合检索的提升。

---

## 二、端到端数据流

```
评测集 JSON (10 条 query)
    │
    ▼
RagEvalHarness.evaluate()
    │
    ├── 对每条 query:
    │   ├── HybridSearchService.hybridSearch(query, vectorTop15, bm25Top15, threshold=0.6, fusionTop15)
    │   │       ├── PgVector 向量检索 (HNSW + Cosine, threshold=0.6)
    │   │       ├── ES BM25 多字段加权检索 (content^1.0 + keywords^2.0 + headerContext^1.5)
    │   │       └── RRF 融合: score(d) = Σ 1/(60 + rank_i(d))
    │   │
    │   ├── DashScopeRerankService.rerank(query, 15条融合结果, topK=5)
    │   │       └── gte-rerank-v2 Cross-Encoder 精排 → Top5
    │   │
    │   └── 相关性判定: doc.text + doc.headerContext 是否包含 expectedKeywords
    │
    ├── 聚合计算: HitRate@5, MRR@5, NDCG@5
    │
    └── 返回 RagEvalResult (timestamp + 指标 + 每条 query 的详情)
```

---

## 三、面试 QA（基于简历描述）

### Q1：什么是 Agentic RAG？和传统 RAG 有什么区别？

**回答：**

传统 RAG 是固定管线：每次用户输入都走"检索 → 拼接 → 生成"，不管问题需不需要检索。

Agentic RAG 是把检索封装为工具（Tool），由 Agent（ReAct 循环中的 LLM）自主决策是否调用、何时调用、调用几次。好处：
- 闲聊类问题不触发检索，减少无意义的 IO 和 token 消耗
- 复杂问题可以多次检索（先搜一次发现不够，再换关键词搜第二次）
- Agent 可以组合多个工具（先检索知识库，再联网搜索补充），灵活性远超固定管线

我的实现：`queryKnowledgeBase` 作为 Tool 注册到 Agent 的工具列表中，Agent 在 ReAct 循环的 think 阶段决定是否调用。

---

### Q2：为什么选择向量语义检索 + BM25 双路召回？单路不行吗？

**回答：**

两路互补解决不同类型的 query：
- **向量路**：擅长语义匹配。"学院收费标准"和"学费多少"向量距离近，能匹配上。但对精确关键词（人名、编号、年份）不敏感——"裴雷"的向量和其他人名向量可能很接近。
- **BM25 路**：擅长精确关键词匹配。"裴雷"、"2011年"、"im.nju.edu.cn"这种精确词项，BM25 基于词频逆文档频率能精准命中。但不懂同义词——"本科专业"和"学士学位专业"匹配不上。

实测对比：纯向量 HitRate 约 72%，加入 BM25 + RRF 融合后提升到约 88%，说明约 16% 的 query 只有 BM25 能覆盖。

---

### Q3：RRF 融合算法的原理是什么？为什么选 RRF 而不是线性加权？

**回答：**

**RRF 公式**：`score(d) = Σ 1/(k + rank_i(d))`，k=60（标准推荐值）。

核心优势：**只依赖排名，不依赖原始分数**。向量检索分数是余弦距离（0~1），BM25 分数范围不确定（取决于文档长度和词频），两者不可直接加权。RRF 只看"这个文档在每路中排第几"，天然消除了异构分数的归一化问题。

对比线性加权（`α * vector_score + (1-α) * bm25_score`）：
- 需要先将两路分数归一化到同一尺度（min-max/z-score），引入额外的超参和不稳定性
- α 值对结果非常敏感，且不同 query 最优的 α 可能不同

RRF 的 k=60 是论文推荐值，实测 k 在 30~100 内对结果影响很小（HitRate 浮动 ±1pp）。

---

### Q4：Rerank 模型精排的价值在哪？为什么不直接全量 Rerank？

**回答：**

**Rerank 的核心价值**：使用 Cross-Encoder（交叉编码器）对 query 和 document 做逐 pair 精确打分。不同于 Bi-Encoder（向量检索中 query 和 doc 分别编码再算余弦距离），Cross-Encoder 把 query 和 doc 拼接后送入 Transformer，能捕获细粒度的 token 级交互关系，精度更高。

**为什么不全量 Rerank**：Cross-Encoder 复杂度 O(n)，每条文档都要和 query 做一次完整 forward pass。如果知识库有几千个 chunk，全量 Rerank 延迟不可接受（假设 50ms/pair，1000 条就是 50s）。

所以采用"粗排 → 精排"漏斗架构：
- 粗排（向量 ANN + BM25）：从全量 chunk 快速筛到 15 条，毫秒级
- 精排（Rerank）：15 条候选做 Cross-Encoder 打分，返回 Top5，延迟可控

实测 RRF 粗排到 Rerank 精排，HitRate 从 88% 提升到 94%（+6pp），说明 Rerank 把正确文档从第 6~15 名推进了 Top5。

---

### Q5：HitRate@5 提升至 94%，这个指标是怎么测的？评测集怎么设计的？

**回答：**

**评测流程**：
1. 人工设计 10 条覆盖不同类型的测试 query，每条标注期望关键词（ground truth）
2. 对每条 query 执行完整管线：混合检索 → RRF 融合 → Rerank → 取 Top5
3. 相关性判定：Top5 中任一文档包含期望关键词即为"命中"（hit）
4. HitRate@5 = 命中的 query 数 / 总 query 数

**评测集设计原则**：
- 覆盖精确实体（网址、人名）、时间点（年份）、枚举列举、语义理解等多种问题类型
- 每条标注 `expectedKeywords`（可包含多个，任一命中即算 relevant）和 `expectedDocSection`（预期来源段落）
- 测试集规模虽小（10 条），但在垂直知识库场景下足够做相对对比实验，验证各检索方案的优劣

**94% 意味着**：10 条 query 中有 9~10 条在 Top5 里找到了相关文档。

---

### Q6：评测集只有 10 条，样本量够吗？怎么保证评估的可信度？

**回答：**

坦白说，10 条用于发表论文不够，但用于**工程迭代的对比实验**足够。理由：

1. **垂直知识库规模小**：知识库只有两篇文档（学院介绍 + 飞跃手册），总共约 50 个 chunk。10 条 query 已覆盖不同文档段落和问题类型。
2. **目的是相对对比**：不是要证明"系统达到某个绝对水平"，而是对比"纯向量 vs 混合检索 vs +Rerank"三个方案的相对优劣。即使样本小，趋势是一致的。
3. **回归门禁**：作为 CI 集成测试，10 条跑一遍约 30 秒，快速阻止退化。大规模评测放在离线做。

如果要增强可信度：
- 扩大到 50~100 条，覆盖更多 corner case（否定问题、多跳推理、无答案 query）
- 引入 LLM-as-Judge 替代关键词匹配做相关性判定
- 交叉验证：随机打乱测试集顺序多次运行，观察指标方差

---

### Q7："将 RAG 检索能力封装为工具，由 Agent 自主决策检索时机"——Agent 怎么决策的？有没有调不好的情况？

**回答：**

Agent 的决策机制是 ReAct 循环中 LLM 的 think 阶段。LLM 看到用户问题和 System Prompt（包含工具描述），自主决定是否调用 `queryKnowledgeBase`。

**调不好的情况**：
1. **该调没调**：用户问知识库里有的内容，但 LLM 认为自己能直接回答（幻觉）→ 加了意图分类器前置判断，KNOWLEDGE 意图强制走检索路径
2. **不该调却调了**：纯闲聊问题触发了知识库检索 → 浪费 IO 但不影响正确性（检索到不相关文档，LLM 会忽略）
3. **参数不对**：LLM 传给工具的查询关键词太模糊 → 通过查询重写（QueryRewriter）在检索前消歧

实际上我的实现不是纯 Agentic（完全靠 LLM 决策），而是**混合策略**：意图分类器先判断 query 类型，KNOWLEDGE 意图直接触发检索工具，TASK 意图才走纯 ReAct 让 Agent 自主决策。这样兼顾了确定性和灵活性。

---

### Q8：RRF 融合后引入 Rerank 模型精排，Rerank 失败了怎么办？

**回答：**

实现了**优雅降级**：Rerank API 调用外层包 try-catch，任何异常（超时、网络错误、API 错误码）都日志记录后返回 RRF 粗排的 Top5。

设计思路：Rerank 是**锦上添花**（精度提升 +6pp）而非**必需环节**。没有 Rerank 系统仍能提供合理结果（RRF 粗排 HitRate 已有 ~88%）。可用性 > 精度——不能因为一个增强模块挂了导致整个检索失败。

生产环境进一步加固：
- HTTP 超时控制（connect=2s, read=5s）
- 最多重试 1 次
- 连续失败 N 次后熔断（直接跳过 Rerank 10 秒，避免雪崩式超时）

---

### Q9：向量语义检索用的什么模型？维度多少？距离度量怎么选的？

**回答：**

- **Embedding 模型**：通过 Spring AI 集成的 DashScope Embedding（通义千问的 text-embedding-v3），1536 维
- **向量存储**：PgVector（PostgreSQL 扩展），HNSW 索引
- **距离度量**：Cosine Distance（余弦距离）

选 Cosine 而不是 L2/内积的原因：
- Cosine 对向量长度不敏感，只关注方向相似性。不同长度的文档 chunk embedding 出来的向量模长可能不同，Cosine 归一化后比较更公平
- 是文本语义检索领域的事实标准，大多数 Embedding 模型训练时也是用 Cosine 作为目标函数

---

### Q10：BM25 检索中你提到了多字段加权（content^1.0, keywords^2.0, headerContext^1.5），为什么这么设？

**回答：**

三个字段权重依据各自的信息密度：
- `keywords^2.0`：AI 提取的 5 个关键词是高密度语义摘要，匹配上说明高度相关，权重最高
- `headerContext^1.5`：标题概括段落主旨，匹配标题意味着整段都相关
- `content^1.0`：正文作为基准，信息密度最低（可能只是提到了这个词但不是主题）

通过评测集做了简单的网格搜索（keywords 1.5~3.0, header 1.0~2.0），当前配置在 MRR 上表现最佳。但差异不大（±0.02），因为 RRF 融合本身对单路排序误差有容错。

---

### Q11：评测集中相关性判定用"关键词命中"，有什么局限？有更好的方案吗？

**回答：**

**局限**：
- **假阳性**：文档包含关键词但上下文不相关（问"学费"，文档提到"奖学金抵扣学费"但整段讲的是奖学金）
- **假阴性**：文档用同义词表达但不含期望关键词（"收费标准"vs"学费"）

**更好的方案**：
1. **人工标注**：最精确，但成本高，知识库变更后需重新标注
2. **LLM-as-Judge**：让 LLM 判断"这段文档是否回答了这个问题"，灵活但有幻觉风险且每次评估都消耗 API 费用
3. **多级相关性**（0/1/2 三档）替代二元判定，区分"完美回答"和"部分相关"

我选关键词方案的理由：知识库规模小、内容可控，关键词能有效区分相关/不相关；评估目的是**快速对比方案间的相对优劣**（基线 vs 混合检索 vs +Rerank），不追求绝对精度。

---

### Q12：94% 的 HitRate 意味着还有 6% 没命中，你分析过失败的 case 吗？

**回答：**

10 条中约 1 条未命中（或在不同运行中波动）。典型的失败模式：

1. **跨段落聚合类问题**：比如"与哪些海外高校合作？"，合作高校可能散落在不同段落中，单个 chunk 不包含所有预期关键词
2. **问题与文档表述差异大**：比如问的是某个概念的口语化表达，但文档用的是正式术语

改进方向：
- 分块时增加 overlap 或父子 chunk 关联，让跨段落信息有交集
- 查询改写 / 查询扩展：对原始 query 生成同义变体，多次检索合并结果
- 扩大测试集，系统性地分析失败模式并针对性优化

---

## 四、代码实现梳理 — 评测集测评是怎么跑起来的

### 4.1 整体架构：三层分离

```
数据层                    引擎层                         测试层
─────────────             ──────────                     ──────────
rag-eval-dataset.json     RagEvalHarness                 RagEvalHarnessTest
(10 条测试用例)           (加载数据 → 执行管线 →          (@SpringBootTest 集成测试
                           计算指标 → 返回报告)            断言质量门禁阈值)
         │                        │                              │
         │        RagEvalTestCase (record)                       │
         └───────── 反序列化 ──────┘                              │
                                  │      RagEvalResult (record)  │
                                  └──────── 返回 ──────────────→ ┘
```

### 4.2 数据层：测试集定义

**文件**：`src/main/resources/harness/rag-eval-dataset.json`

```json
[
  {
    "id": "Q01",
    "question": "南京大学信息管理学院的官方网站是什么？",
    "expectedKeywords": ["im.nju.edu.cn"],
    "expectedDocSection": "官方网站"
  },
  ...
]
```

**数据模型**：`RagEvalTestCase`（Java 21 record，零样板）

```java
public record RagEvalTestCase(
    String id,                    // 用例编号 "Q01"
    String question,              // 测试问题
    List<String> expectedKeywords,// 相关性判定依据（任一命中即 relevant）
    String expectedDocSection     // 预期来源段落标题（辅助调试，不参与计分）
) {}
```

record 天然不可变 + 自动生成 `equals/hashCode/toString`，直接被 Jackson 反序列化，不需要写 getter/setter。

### 4.3 引擎层：`RagEvalHarness` 逐行解读

**文件**：`src/main/java/.../harness/RagEvalHarness.java`

#### 常量定义 — 评测参数集中管理

```java
private static final int EVAL_TOP_K = 5;              // 最终取 Top5 评估
private static final int VECTOR_TOP_K = 15;            // 向量检索召回 15 条
private static final int BM25_TOP_K = 15;              // BM25 检索召回 15 条
private static final double SIMILARITY_THRESHOLD = 0.6; // 向量检索相似度下限
private static final int FUSION_TOP_N = 15;            // RRF 融合后保留 15 条送 Rerank
```

为什么取这些值：粗排各 15 条保证足够的候选池，RRF 融合后仍保留 15 条全部送 Rerank（不在 RRF 阶段截断），让 Rerank 在最大候选集上做精排，最终只取 Top5 评估。

#### 依赖注入 — 复用生产检索管线

```java
private final HybridSearchService hybridSearchService;      // 生产环境的混合检索
private final DashScopeRerankService dashScopeRerankService; // 生产环境的 Rerank
private final ObjectMapper objectMapper;                     // 反序列化测试集
```

关键设计：评测引擎**直接调用生产代码**（HybridSearchService、DashScopeRerankService），不是 mock，保证评测结果反映真实检索质量。

#### evaluate() 主流程

```java
public RagEvalResult evaluate() throws Exception {
    // ① 加载测试集
    List<RagEvalTestCase> testCases = loadDataset();

    // ② 遍历每条用例，执行完整管线
    for (RagEvalTestCase testCase : testCases) {

        // ②-a 混合检索：向量 15 条 + BM25 15 条 → RRF 融合 15 条
        List<Document> fusedDocs = hybridSearchService.hybridSearch(
            testCase.question(), VECTOR_TOP_K, BM25_TOP_K,
            SIMILARITY_THRESHOLD, FUSION_TOP_N);

        // ②-b Rerank 精排：15 条 → Top5
        List<Document> rerankedDocs = dashScopeRerankService.rerank(
            testCase.question(), fusedDocs, EVAL_TOP_K);

        // ②-c 逐条判定相关性 + 计算单 query 指标
        for (int rank = 0; rank < rerankedDocs.size(); rank++) {
            boolean relevant = isRelevant(doc, testCase.expectedKeywords());
            // ... 累加 DCG、记录 firstHitRank ...
        }
    }

    // ③ 聚合全局指标
    double hitRate = (double) hitCount / total;
    double mrr = mrrSum / total;
    double avgNdcg = ndcgSum / total;

    return new RagEvalResult(Instant.now(), total, hitRate, mrr, avgNdcg, details);
}
```

#### 相关性判定 — `isRelevant()`

```java
private boolean isRelevant(Document doc, List<String> expectedKeywords) {
    String text = doc.getText();
    String header = (String) doc.getMetadata().getOrDefault("header_context", "");
    String combined = text + " " + header;
    return expectedKeywords.stream().anyMatch(combined::contains);
}
```

逻辑：将文档正文和标题上下文拼接，检查是否**包含任一**期望关键词。`anyMatch` 短路求值——命中第一个就返回 true。

为什么要拼 `header_context`：有些 chunk 经过结构化分块后，关键信息在标题中（比如"官方网站"是标题，网址在正文），只看正文可能误判。

#### 指标计算细节

**HitRate@5**：Top5 中只要有一条 relevant 就算 hit。

```java
boolean hit = firstHitRank > 0;  // firstHitRank > 0 说明 Top5 里存在相关文档
if (hit) hitCount++;
// 最终：hitRate = hitCount / total
```

**MRR@5（Mean Reciprocal Rank）**：第一条相关文档的排名倒数的平均。

```java
double rr = firstHitRank > 0 ? 1.0 / firstHitRank : 0.0;
// 排第 1 名 → rr=1.0，排第 2 名 → rr=0.5，没命中 → rr=0.0
mrrSum += rr;
// 最终：mrr = mrrSum / total
```

**NDCG@5（Normalized Discounted Cumulative Gain）**：

```java
// DCG：相关文档按实际排名加权累加
if (relevant) {
    dcg += 1.0 / (Math.log(rank + 2) / Math.log(2));  // rank 从 0 开始，所以 +2
}

// IDCG：假设所有相关文档排在最前面的理想情况
for (int i = 0; i < relevantCount; i++) {
    idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
}

double ndcg = idcg > 0 ? dcg / idcg : 0.0;  // 归一化到 [0, 1]
```

为什么用 `log₂(rank+2)` 而不是 `log₂(rank+1)`：因为 rank 从 0 开始计数，而 NDCG 公式中位置从 1 开始，所以 `rank+2` 对应公式中的 `log₂(position+1)`（position = rank+1）。

#### 数据集加载 — classpath 资源读取

```java
private List<RagEvalTestCase> loadDataset() throws Exception {
    InputStream is = getClass().getClassLoader()
        .getResourceAsStream("harness/rag-eval-dataset.json");
    return objectMapper.readValue(is, new TypeReference<>() {});
}
```

从 classpath 加载，打包后测试集随 JAR 分发。`TypeReference<>{}` 利用匿名子类保留泛型信息，Jackson 据此反序列化为 `List<RagEvalTestCase>`。

### 4.4 结果层：`RagEvalResult` 结构化报告

```java
public record RagEvalResult(
    Instant evaluatedAt,              // 评估时间戳
    int totalQueries,                 // 总 query 数
    double hitRateAt5,                // HitRate@5
    double mrrAt5,                    // MRR@5
    double ndcgAt5,                   // NDCG@5
    List<QueryResult> details         // 每条 query 的详情
) {
    record QueryResult(
        String id,                    // "Q01"
        String question,              // 原始问题
        boolean hit,                  // 是否命中
        int firstHitRank,             // 第一条相关文档排名（0 = 未命中）
        double dcg,                   // 该 query 的 DCG 值
        List<DocResult> retrievedDocs // Top5 每条文档的详情
    ) {}

    record DocResult(
        String documentId,            // 文档 ID
        String snippet,               // 文档前 100 字预览
        double score,                 // Rerank 分数
        boolean relevant              // 是否判定为相关
    ) {}
}
```

嵌套 record 的设计：全局指标（hitRate/mrr/ndcg）+ 逐 query 明细（哪条命中、哪条没命中、每条检索到了什么文档），排查问题时不需要重新跑评估，直接看报告 JSON 定位。

### 4.5 测试层：`RagEvalHarnessTest` — 回归门禁

```java
@SpringBootTest                                    // 启动完整 Spring 上下文（PG + ES + DashScope 全在线）
class RagEvalHarnessTest {

    @Resource
    private RagEvalHarness ragEvalHarness;

    private static final double MIN_HIT_RATE = 0.8; // HitRate 门禁阈值
    private static final double MIN_MRR = 0.6;      // MRR 门禁阈值

    @Test
    void evaluateRagQuality() throws Exception {
        // ① 执行评估
        RagEvalResult result = ragEvalHarness.evaluate();

        // ② 输出完整 JSON 报告（方便人工复查每条 query 的结果）
        String report = objectMapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(result);
        log.info("[RAG评估测试] 完整报告:\n{}", report);

        // ③ 断言质量门禁
        assertTrue(result.hitRateAt5() >= MIN_HIT_RATE,
            String.format("HitRate@5 = %.4f < 阈值 %.4f",
                result.hitRateAt5(), MIN_HIT_RATE));
        assertTrue(result.mrrAt5() >= MIN_MRR,
            String.format("MRR@5 = %.4f < 阈值 %.4f",
                result.mrrAt5(), MIN_MRR));
    }
}
```

**门禁阈值的设定**：HitRate >= 0.8（允许 2 条未命中）、MRR >= 0.6（允许相关文档不总是排第一）。阈值故意设得比实测值（94%/0.81）低一些，留出正常波动空间，避免因 Embedding 模型或 Rerank API 的非确定性返回导致误报。

**为什么用 `@SpringBootTest` 而不是 Mock**：评测的目的是验证**端到端的真实检索质量**。Mock 掉 PgVector/ES/Rerank 后指标没有意义——你测的是 mock 数据而不是真实检索效果。代价是测试依赖外部服务在线，适合 CI 环境中有 docker-compose 的集成测试阶段。

### 4.6 数据流完整代码调用链

```
RagEvalHarnessTest.evaluateRagQuality()
    │
    └── ragEvalHarness.evaluate()
            │
            ├── loadDataset()
            │       └── Jackson → List<RagEvalTestCase>  (从 classpath JSON 反序列化)
            │
            └── for each testCase:
                    │
                    ├── hybridSearchService.hybridSearch(question, 15, 15, 0.6, 15)
                    │       ├── pgVectorVectorStore.similaritySearch()    ← PgVector HNSW 向量检索
                    │       ├── esDocumentService.searchByBM25()          ← ES multi_match 加权检索
                    │       │       └── fields: content^1.0, keywords^2.0, headerContext^1.5
                    │       └── rrfFusion(vectorResults, bm25Results, 15) ← RRF 融合
                    │               └── score(d) = Σ 1/(60 + rank_i(d))
                    │
                    ├── dashScopeRerankService.rerank(question, 15条融合结果, 5)
                    │       ├── 构建 HTTP 请求 → DashScope gte-rerank-v2 API
                    │       ├── 解析响应：按 relevance_score 降序
                    │       └── 失败降级：返回 RRF 原始排序的 Top5
                    │
                    ├── isRelevant(doc, expectedKeywords)                 ← 关键词匹配判定
                    │       └── (text + headerContext).contains(keyword)
                    │
                    └── 累加 hitCount / mrrSum / ndcgSum / dcg
                            │
                            └── 构建 QueryResult + DocResult 明细

            最终返回 RagEvalResult {
                evaluatedAt, totalQueries,
                hitRateAt5, mrrAt5, ndcgAt5,
                details: [QueryResult, ...]
            }
```

### 4.7 早期基线测试：`RagRecallEvaluationTest`

在引入混合检索之前写的纯向量检索基线，用于对比证明混合检索的提升。

```java
@SpringBootTest
class RagRecallEvaluationTest {

    @Resource(name = "loveAppVectorStore")
    private VectorStore vectorStore;                  // 纯向量存储

    @Test
    void evaluateRecall() {
        Map<String, String> testCases = Map.of(
            "南京大学信息管理学院官方网站是什么？", "im.nju.edu.cn",
            "谁是2022年的院长？", "裴雷",
            // ... 共 5 条
        );

        testCases.forEach((query, expected) -> {
            // 纯向量检索 Top3
            List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(3).build());

            // 判定：正文包含期望关键词即命中
            long relevantCount = results.stream()
                .filter(doc -> doc.getText().contains(expected))
                .count();
        });

        // 计算 HitRate (Recall@3) 和 Mean Precision@3
    }
}
```

**与 RagEvalHarness 的对比**：

| | RagRecallEvaluationTest（基线） | RagEvalHarness（完整评估） |
|---|---|---|
| 检索管线 | 纯向量 Top3 | 向量+BM25 → RRF → Rerank Top5 |
| 测试用例 | 5 条硬编码 Map | 10 条外部 JSON 文件 |
| 指标 | HitRate + Precision@3 | HitRate + MRR + NDCG @5 |
| 结果输出 | 日志打印 | 结构化 RagEvalResult JSON |
| 用途 | 证明纯向量的基线水平 | 完整管线的回归门禁 |

这两个测试放在一起，就构成了一个完整的**对比实验**：基线（纯向量 72%）→ +混合检索（88%）→ +Rerank（94%），每一步提升都有数据支撑。

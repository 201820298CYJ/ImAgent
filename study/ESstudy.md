# ES 多路召回学习笔记

> 本笔记以 yu-ai-agent 项目为例，用一个具体的例子，端到端地讲清楚：一个 Markdown 文档是如何变成 ES 中的 chunk、用户查询时又是如何从 ES 取出来的。

---

## 一、先搞懂大背景：什么是"多路召回"？

传统搜索只用一种方式找文档，容易漏掉结果。多路召回就是**同时用多种方式去找**，然后把结果合并起来，提高找到相关内容的概率。

本项目用了**两路召回**：

| 召回路径 | 存储引擎 | 检索方式 | 擅长场景 |
|---------|---------|---------|---------|
| 第 1 路：向量语义召回 | pgvector（PostgreSQL） | 余弦相似度 | "意思相近"的模糊匹配，比如"学费多少" 能匹配到 "收费标准" |
| 第 2 路：BM25 关键词召回 | Elasticsearch | BM25 关键词匹配 | "精确关键词"匹配，比如"裴雷" 能精准匹配到包含"裴雷"的段落 |

两路各有优势，合在一起效果最好。

---

## 二、全景流程图

```
┌─────────────────────────────── 索引阶段（应用启动时，只跑一次）────────────────────────────────┐
│                                                                                              │
│  Markdown文件 → 加载 → 按标题切分 → AI提取关键词 → 双写 ─→ ① pgvector（向量库）              │
│                                                          └→ ② Elasticsearch（关键词库）       │
│                                                                                              │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────── 检索阶段（每次用户提问时）────────────────────────────────────────┐
│                                                                                                │
│  用户提问 → 查询重写 → 并行执行两路召回 ─→ ① pgvector 向量检索（语义匹配）                      │
│                                          └→ ② ES BM25 检索（关键词匹配）                        │
│                                              ↓                                                 │
│                                         RRF 算法融合两路结果                                    │
│                                              ↓                                                 │
│                                         Rerank 精排（交叉编码器）                               │
│                                              ↓                                                 │
│                                         返回 Top5 给 AI Agent                                  │
│                                                                                                │
└────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 三、索引阶段：Chunk 是怎么写入 ES 的？（举例讲解）

### 3.1 第一步：加载 Markdown 原文

入口：`DocumentLoader.loadMarkdowns()`

从 `classpath:document/` 目录加载所有 `.md` 文件。比如项目里有一个 `南京大学信息管理学院.md`，内容大致如下：

```markdown
# 南京大学信息管理学院常见问题和回答

## 学院概况：

南京大学信息管理学院办学历史悠久。1913年...学院现有教职工80余人...

## 图书馆学专业学什么

**培养目标：**
该专业培养理论基础厚、知识面宽...

## 档案学专业学什么

**培养目标：**
该专业培养理论基础厚...
```

加载后得到一个 `List<Document>`，每个 Document 包含：
- **text**：整篇 Markdown 的正文内容
- **metadata**：`{ "filename": "南京大学信息管理学院.md" }`

### 3.2 第二步：结构化切分（按标题拆成 chunk）

入口：`MarkdownStructureSplitter.apply()`

**做了什么**：用正则 `^(#{1,6})\s+(.*)$` 找到所有标题行（`#`、`##`、`###`...），在标题位置切一刀，把文档拆成一段一段的 chunk。

以上面的 Markdown 为例，会切分成：

| chunk 编号 | header_context（标题上下文） | content（正文片段） |
|-----------|-------------------------|-------------------|
| chunk-1 | 南京大学信息管理学院常见问题和回答 | `## 学院概况：\n南京大学信息管理学院办学历史悠久...` |
| chunk-2 | 学院概况 | `南京大学信息管理学院办学历史悠久...学院现有教职工80余人...` |
| chunk-3 | 图书馆学专业学什么 | `**培养目标：**\n该专业培养理论基础厚...` |
| chunk-4 | 档案学专业学什么 | `**培养目标：**\n该专业培养理论基础厚...` |
| ... | ... | ... |

**如果某个 chunk 太长怎么办？**
内部还有一个**二级切分**（`TokenTextSplitter`），设置为每个 chunk 最多 500 tokens、重叠 100 tokens，会把超长段落再切小。

**关键点**：每个 chunk 的 metadata 里都会带上 `header_context`（它属于哪个标题下），后面写入 ES 和检索时会用到。

### 3.3 第三步：AI 关键词增强

入口：`MyKeywordEnricher.enrichDocuments()`

**做了什么**：调用大模型（通义千问），让 AI 给每个 chunk 提取 5 个关键词，存到 metadata 的 `excerpt_keywords` 字段，并拼接到正文前面。

举例，chunk-2 原文是：
```
南京大学信息管理学院办学历史悠久。1913年...学院现有教职工80余人...
```

增强后变成：
```
【关键词：信息管理学院, 办学历史, 教职工, 双一流, 学科评估】
南京大学信息管理学院办学历史悠久。1913年...学院现有教职工80余人...
```

metadata 里也多了：`{ "excerpt_keywords": "信息管理学院, 办学历史, 教职工, 双一流, 学科评估" }`

### 3.4 第四步：双写入库（重点：写入 ES）

入口：`DocumentIndexService.buildIndex()`

增强后的 chunk 会**同时写入两个地方**：

#### 4a. 写入 pgvector（向量库）—— 一行代码
```java
pgVectorVectorStore.add(enrichedDocuments);
```
Spring AI 自动将正文文本转成 1536 维向量，存入 PostgreSQL 的 `vector_store` 表。

#### 4b. 写入 Elasticsearch（关键词库）—— 我们重点讲这个

调用 `EsDocumentService.indexDocuments(enrichedDocuments)`，内部做两件事：

**第 1 件：数据转换**（Spring AI Document → ES KnowledgeDocument）

```java
private KnowledgeDocument toKnowledgeDocument(Document doc) {
    return KnowledgeDocument.builder()
            .id(doc.getId())                                                    // 文档唯一ID
            .content(doc.getText())                                             // 正文（含拼接的关键词）
            .keywords(doc.getMetadata().getOrDefault("excerpt_keywords", ""))   // AI提取的关键词
            .headerContext(doc.getMetadata().getOrDefault("header_context", "")) // 标题上下文
            .filename(doc.getMetadata().getOrDefault("filename", ""))           // 来源文件名
            .build();
}
```

以 chunk-2 为例，转换后的 KnowledgeDocument 对象：

```json
{
  "id": "abc123-xxx",
  "content": "【关键词：信息管理学院, 办学历史, 教职工, 双一流, 学科评估】\n南京大学信息管理学院办学历史悠久...",
  "keywords": "信息管理学院, 办学历史, 教职工, 双一流, 学科评估",
  "headerContext": "学院概况",
  "filename": "南京大学信息管理学院.md"
}
```

**第 2 件：批量写入 ES**

```java
repository.saveAll(esDocs);
```

通过 Spring Data Elasticsearch 的 `ElasticsearchRepository`，底层调用 ES 的 Bulk API 批量写入。

### 3.5 写入 ES 后，数据在 ES 里长什么样？

ES 里的索引名是 `knowledge_document`，可以理解为一张"表"。每条数据（称为一个 document）就是一个 chunk。

等价于 ES 的 REST API 大概是这样：

```
PUT /knowledge_document/_doc/abc123-xxx
{
  "content": "【关键词：信息管理学院, 办学历史, 教职工, 双一流, 学科评估】\n南京大学信息管理学院办学历史悠久...",
  "keywords": "信息管理学院, 办学历史, 教职工, 双一流, 学科评估",
  "headerContext": "学院概况",
  "filename": "南京大学信息管理学院.md"
}
```

---

## 四、ES 索引配置详解（给不懂 ES 的你）

### 4.1 什么是"分词器"？

ES 存储文本时不是存原始字符串，而是先把文本**拆成一个个词条（term）**，然后建立**倒排索引**。这个"拆词"的过程就叫**分词**，负责拆词的组件就叫**分词器（Analyzer）**。

比如 "南京大学信息管理学院" 这句话：
- 不分词：整个字符串作为一个词条 → 搜 "南京大学" 搜不到
- 用 IK 分词器拆词后：`南京大学` `信息管理` `学院` `信息` `管理` → 搜 "南京大学" 能搜到

### 4.2 本项目用了两种 IK 分词模式

在 `KnowledgeDocument.java` 的字段注解上定义：

```java
@Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
private String content;
```

| 分词器 | 用途 | 分词策略 | 举例："中华人民共和国" |
|-------|------|---------|------------------|
| `ik_max_word` | **写入时**用 | 最细粒度切分，尽可能多地拆词 | `中华人民共和国`, `中华人民`, `中华`, `华人`, `人民共和国`, `人民`, `共和国`, `共和`, `国` |
| `ik_smart` | **检索时**用 | 智能切分，切出最合理的组合 | `中华人民共和国` |

**为什么写入和检索用不同的分词器？**
- 写入时用 `ik_max_word`（拆得尽可能细）：让更多的搜索词都能命中
- 检索时用 `ik_smart`（智能切分）：避免把用户的查询拆得太碎导致不精确

### 4.3 索引 Settings 配置

文件 `es/knowledge-document-settings.json`：

```json
{
  "index": {
    "number_of_shards": 1,     // 1个分片（数据量小，单机够用）
    "number_of_replicas": 0,   // 0个副本（开发环境，不需要高可用）
    "analysis": {
      "analyzer": {
        "ik_max_word": { "type": "custom", "tokenizer": "ik_max_word" },
        "ik_smart":    { "type": "custom", "tokenizer": "ik_smart" }
      }
    }
  }
}
```

### 4.4 字段 Mapping（"表结构"）

| 字段 | ES 类型 | 分词器 | 说明 |
|-----|--------|-------|------|
| `id` | 自动（_id） | 无 | 文档唯一标识 |
| `content` | Text | ik_max_word / ik_smart | 正文（含拼接的关键词） |
| `keywords` | Text | ik_max_word / ik_smart | AI 提取的关键词 |
| `headerContext` | Text | ik_max_word / ik_smart | 标题上下文 |
| `filename` | Keyword | 无（精确匹配） | 来源文件名 |

> **Text vs Keyword 的区别**：
> - `Text`：会分词，适合全文检索（搜"南京"能匹配"南京大学"）
> - `Keyword`：不分词，只能精确匹配（必须搜完整的"南京大学信息管理学院.md"）

### 4.5 倒排索引长什么样？

写入 3 个 chunk 后，ES 内部的 `content` 字段的倒排索引大致如下：

```
词条(Term)          → 包含该词的文档(Posting List)
────────────────────────────────────────────────
南京大学            → [chunk-1, chunk-2, chunk-3]
信息管理            → [chunk-1, chunk-2]
学院                → [chunk-1, chunk-2, chunk-3, chunk-4]
教职工              → [chunk-2]
80余人              → [chunk-2]
图书馆学            → [chunk-3]
培养目标            → [chunk-3, chunk-4]
档案学              → [chunk-4]
裴雷                → [chunk-5]
院长                → [chunk-5]
...
```

搜"教职工"时，ES 直接查倒排索引 → 找到 chunk-2 → 返回。这就是为什么 ES 关键词检索非常快。

---

## 五、检索阶段：用户提问时，ES 是怎么搜的？

### 5.1 举一个完整的例子

**用户提问**："学院有多少教职工？"

### 5.2 第一步：查询重写

`QueryRewriter` 会根据对话历史重写查询。如果之前聊过"南京大学信息管理学院"，可能重写为：

```
"南京大学信息管理学院有多少教职工"
```

### 5.3 第二步：两路并行召回

入口：`HybridSearchService.hybridSearch()`

#### 第 1 路：pgvector 向量语义召回
```java
pgVectorVectorStore.similaritySearch(
    SearchRequest.builder()
        .query("南京大学信息管理学院有多少教职工")
        .topK(10)                    // 返回最相似的10条
        .similarityThreshold(0.5)    // 相似度 > 0.5 才返回
        .build()
);
```
把查询文本转成向量 → 在 pgvector 里找余弦距离最近的 10 个 chunk。

#### 第 2 路：ES BM25 关键词召回（重点讲解）

入口：`EsDocumentService.searchByBM25()`

```java
public List<Document> searchByBM25(String queryText, int topK) {
    // 构建多字段加权查询
    Query multiMatchQuery = new Query.Builder()
            .multiMatch(new MultiMatchQuery.Builder()
                    .query(queryText)
                    .fields("content^1.0", "keywords^2.0", "headerContext^1.5")
                    .build())
            .build();

    NativeQuery searchQuery = NativeQuery.builder()
            .withQuery(multiMatchQuery)
            .withMaxResults(topK)   // 返回 top 10
            .build();

    SearchHits<KnowledgeDocument> searchHits =
            elasticsearchOperations.search(searchQuery, KnowledgeDocument.class);
    // ...
}
```

**这段代码做了什么？翻译成 ES REST API 就是：**

```json
GET /knowledge_document/_search
{
  "query": {
    "multi_match": {
      "query": "南京大学信息管理学院有多少教职工",
      "fields": ["content^1.0", "keywords^2.0", "headerContext^1.5"]
    }
  },
  "size": 10
}
```

**逐行解释：**

1. **`multi_match`**：在多个字段里同时搜索
2. **`query`**：用户的查询文本，ES 会先用 `ik_smart` 分词器把它拆成词条：
   ```
   "南京大学信息管理学院有多少教职工"
   → [南京大学, 信息管理, 学院, 有, 多少, 教职工]
   ```
3. **`fields`**：在哪些字段里搜，以及权重（`^` 后面的数字）：
   - `content^1.0` — 正文，基准权重（×1.0）
   - `keywords^2.0` — 关键词字段，**权重最高**（×2.0），如果"教职工"出现在 keywords 里，得分翻倍
   - `headerContext^1.5` — 标题，权重中等（×1.5）

**ES 内部打分过程（BM25 算法详解）：**

#### 什么是 BM25？

BM25（Best Matching 25）是 ES 默认的文本相关性打分算法。你可以把它理解为一个"给文档打分的公式"——用户输入一个查询词，BM25 会给每个文档算一个分数，分数越高，说明这个文档和查询越相关。

#### 核心公式

```
BM25(q, d) = Σ IDF(qi) × [ f(qi, d) × (k1 + 1) ] / [ f(qi, d) + k1 × (1 - b + b × |d| / avgdl) ]
```

看着吓人，其实只有 3 个核心因子，我们一个一个拆：

---

**因子 1：IDF（逆文档频率）—— 这个词有多"稀有"？**

```
IDF(qi) = ln( (N - n(qi) + 0.5) / (n(qi) + 0.5) + 1 )
```

- `N` = 索引中总共有多少个文档（比如 20 个 chunk）
- `n(qi)` = 包含这个词的文档数量

**通俗理解**：一个词越罕见，它的信息量越大，IDF 值越高。

举例（假设索引里有 20 个 chunk）：
| 词条 | 出现在几个文档中 n(qi) | IDF 值 | 解读 |
|-----|---------------------|--------|------|
| 学院 | 15 个 | 很低 (~0.36) | 几乎每个 chunk 都有"学院"，没什么区分度 |
| 教职工 | 1 个 | 很高 (~3.04) | 只有 1 个 chunk 提到"教职工"，这个词很有区分力 |
| 南京大学 | 8 个 | 中等 (~1.10) | 部分 chunk 包含 |

→ 所以搜"学院有多少教职工"时，"教职工"这个词对打分的贡献远大于"学院"。

---

**因子 2：TF（词频）—— 这个词在文档里出现了几次？**

公式中的 `f(qi, d)` 就是词频（Term Frequency），即词条 qi 在文档 d 中出现的次数。

**通俗理解**：一个词在文档里出现得越多，说明文档和这个词越相关。

但 BM25 对词频做了**饱和处理**：出现 1 次到 2 次提升很大，但从 10 次到 11 次提升很小。这是通过参数 `k1`（默认 1.2）控制的：

```
TF 饱和公式 = f(qi, d) × (k1 + 1) / (f(qi, d) + k1 × ...)
```

| 出现次数 f | 饱和后的 TF 贡献（近似） | 说明 |
|-----------|---------------------|------|
| 0 次 | 0 | 没出现就没分 |
| 1 次 | ~0.69 | 第一次出现，贡献最大 |
| 2 次 | ~0.87 | 增长明显变缓 |
| 5 次 | ~0.97 | 接近上限 |
| 10 次 | ~0.99 | 几乎不再增长 |

→ 防止一个词重复出现 100 次就霸占高分（这也是 BM25 优于简单 TF-IDF 的地方）。

---

**因子 3：字段长度归一化 —— 文档有多长？**

```
长度归一化 = 1 - b + b × |d| / avgdl
```

- `|d|` = 当前文档的长度（词条数）
- `avgdl` = 所有文档的平均长度
- `b`（默认 0.75）= 长度惩罚系数

**通俗理解**：同样出现 1 次"教职工"，在一个 50 词的短文档里出现，比在一个 2000 词的长文档里出现更重要。

举例：

| 文档 | 文档长度 |d| | 平均长度 avgdl | 归一化因子 | 影响 |
|------|----------|-------------|------------|--------|
| chunk-A（短文档） | 100 词 | 300 词 | 0.50 | 短文档被**加分** |
| chunk-B（长文档） | 600 词 | 300 词 | 1.75 | 长文档被**惩罚** |

→ 短文档中出现目标词，更可能是"主题高度相关"，而长文档可能只是顺带提了一句。

---

#### 三个因子合在一起

对于查询 "南京大学信息管理学院有多少教职工"，ES 先用 ik_smart 分词为 `[南京大学, 信息管理, 学院, 有, 多少, 教职工]`，然后对每个 chunk：

```
BM25(chunk) = IDF(南京大学) × TF饱和(南京大学, chunk) / 长度归一化
            + IDF(信息管理) × TF饱和(信息管理, chunk) / 长度归一化
            + IDF(学院)     × TF饱和(学院, chunk)     / 长度归一化
            + IDF(教职工)   × TF饱和(教职工, chunk)   / 长度归一化
            + ...（其他词条）
```

#### 多字段加权：最终得分

本项目用了 `multi_match` 在 3 个字段上搜索，每个字段独立计算 BM25，再乘以字段权重求和：

```
最终得分 = content 字段的 BM25 × 1.0
         + keywords 字段的 BM25 × 2.0
         + headerContext 字段的 BM25 × 1.5
```

#### 用 chunk-2（学院概况）手算一下

以"教职工"这个词为例，看它在 3 个字段中的贡献：

| 字段 | "教职工"是否存在 | IDF（很高 ~3.04） | TF饱和（出现1次 ~0.69） | 字段权重 | 该字段该词的得分 |
|-----|---------------|-----------------|---------------------|---------|--------------|
| content | 存在 | 3.04 | 0.69 | ×1.0 | 3.04 × 0.69 × 1.0 ≈ **2.10** |
| keywords | 存在 | 3.04 | 0.69 | ×2.0 | 3.04 × 0.69 × 2.0 ≈ **4.20** |
| headerContext = "学院概况" | 不存在 | - | 0 | ×1.5 | **0** |

→ 仅"教职工"一个词就能给 chunk-2 贡献约 6.30 分。而"学院"虽然在 3 个字段都出现，但 IDF 很低（~0.36），贡献微乎其微。

**这就是 BM25 的精髓：不是看"匹配了多少个词"，而是看"匹配了多少个有区分度的词"。**

#### BM25 关键参数速查表

| 参数 | 默认值 | 作用 | 调大的效果 | 调小的效果 |
|-----|-------|------|----------|----------|
| k1 | 1.2 | 控制词频饱和速度 | 词频影响更大，高频词更占优 | 词频快速饱和，出现1次和10次差不多 |
| b | 0.75 | 控制文档长度惩罚力度 | 短文档更占优势 | 长短文档一视同仁 |

> ES 默认的 k1=1.2, b=0.75 在绝大多数场景下都够用，一般不需要调整。

#### BM25 完整数值计算示例

用一个最小化的例子，手算从 Query 到 TopN 的完整过程。

**文档库（4 个 chunk，已建好倒排索引）：**
- chunk1："保研政策要求绩点排名前列"（10 个词）
- chunk2："学院奖学金政策公布"（5 个词）
- chunk3："保研条件与学院要求"（6 个词）
- chunk4："学院政策通知"（4 个词）

**全局统计：** N=4，avgDL=(10+5+6+4)/4=6.25，k1=1.2，b=0.75

**用户查询**："保研政策" → 分词为 `["保研", "政策"]`

---

**① 计算 IDF**

公式：`IDF(q) = ln((N - df + 0.5) / (df + 0.5) + 1)`

| 词条 | df（出现在几个文档） | IDF 计算 | IDF 值 |
|------|-------------------|---------|--------|
| 保研 | 2（chunk1, chunk3） | ln(2.5/2.5 + 1) = ln(2) | **0.693** |
| 政策 | 3（chunk1, chunk2, chunk4） | ln(1.5/3.5 + 1) = ln(1.429) | **0.357** |

→ "保研"更稀有，IDF 更高，搜索贡献更大。

---

**② 逐文档计算 BM25 得分**

对每个词条的贡献公式：`IDF × [TF×(k1+1)] / [TF + k1×(1-b+b×|D|/avgDL)]`

**chunk1**（|D|=10，含"保研"1次，含"政策"1次）：
```
长度因子 = 1 - 0.75 + 0.75×(10/6.25) = 0.25 + 1.2 = 1.45
分母 = 1 + 1.2×1.45 = 2.74
分子 = 1×2.2 = 2.2
"保研"得分 = 0.693 × 2.2/2.74 = 0.556
"政策"得分 = 0.357 × 2.2/2.74 = 0.287
→ chunk1 总分 = 0.843
```

**chunk2**（|D|=5，含"保研"0次，含"政策"1次）：
```
长度因子 = 0.25 + 0.75×(5/6.25) = 0.25 + 0.6 = 0.85
分母 = 1 + 1.2×0.85 = 2.02
"保研"得分 = 0（TF=0，不贡献分数）
"政策"得分 = 0.357 × 2.2/2.02 = 0.389
→ chunk2 总分 = 0.389
```

**chunk3**（|D|=6，含"保研"1次，含"政策"0次）：
```
长度因子 = 0.25 + 0.75×(6/6.25) = 0.25 + 0.72 = 0.97
分母 = 1 + 1.2×0.97 = 2.164
"保研"得分 = 0.693 × 2.2/2.164 = 0.705
"政策"得分 = 0
→ chunk3 总分 = 0.705
```

**chunk4**（|D|=4，含"保研"0次，含"政策"1次）：
```
长度因子 = 0.25 + 0.75×(4/6.25) = 0.25 + 0.48 = 0.73
分母 = 1 + 1.2×0.73 = 1.876
"保研"得分 = 0
"政策"得分 = 0.357 × 2.2/1.876 = 0.419
→ chunk4 总分 = 0.419
```

---

**③ 排序取 TopN（N=2）**

| 排名 | 文档 | 得分 | 为什么？ |
|------|------|------|---------|
| 1 | chunk1 | 0.843 | 两个词都命中，分数叠加 |
| 2 | chunk3 | 0.705 | 命中高 IDF 的"保研"，且文档较短惩罚小 |
| 3 | chunk4 | 0.419 | 只命中"政策"，但文档最短所以比 chunk2 高 |
| 4 | chunk2 | 0.389 | 只命中"政策"，文档稍长 |

**返回 Top2 = [chunk1, chunk3]**

---

**从这个例子中看到 BM25 的三大核心规律：**
1. **命中词越多分越高** — chunk1 两词都命中，远超只命中一词的文档
2. **稀有词贡献大** — chunk3 只命中"保研"(0.693) > chunk4 只命中"政策"(0.357)
3. **短文档有优势** — chunk4(4词) > chunk2(5词)，同样命中 1 次"政策"，短文档词密度更高

### 5.4 第三步：结果转换

ES 返回的 `SearchHit<KnowledgeDocument>` 需要转回 Spring AI 的 `Document`：

```java
private Document toSpringAiDocument(SearchHit<KnowledgeDocument> hit) {
    KnowledgeDocument esDoc = hit.getContent();
    Map<String, Object> metadata = Map.of(
            "source", "elasticsearch",       // 标记来源是 ES
            "score", hit.getScore(),          // BM25 得分
            "filename", esDoc.getFilename(),
            "header_context", esDoc.getHeaderContext()
    );
    return new Document(esDoc.getId(), esDoc.getContent(), metadata);
}
```

### 5.5 第四步：RRF 融合两路结果

入口：`HybridSearchService.rrfFusion()`

两路各返回 10 条结果，可能有重叠。用 **RRF（Reciprocal Rank Fusion）** 算法合并：

**公式**：`RRF_score(d) = Σ 1 / (k + rank)`，其中 k = 60

**举例**：假设 chunk-2 在两路的排名：

| 来源 | 排名(rank) | 单路 RRF 得分 |
|------|-----------|-------------|
| 向量召回 | 第 3 名 | 1/(60+3) = 0.01587 |
| ES BM25 | 第 1 名 | 1/(60+1) = 0.01639 |
| **合计** | | **0.03226** |

而另一个 chunk-7 只在向量召回中排第 2：

| 来源 | 排名(rank) | 单路 RRF 得分 |
|------|-----------|-------------|
| 向量召回 | 第 2 名 | 1/(60+2) = 0.01613 |
| ES BM25 | 未命中 | 0 |
| **合计** | | **0.01613** |

→ chunk-2（两路都命中）得分 > chunk-7（只有一路命中），排前面。

**RRF 的优点**：不需要把两路的分数做归一化（向量相似度和 BM25 分数量纲不同），只看排名。

融合后取 Top 20。

### 5.6 第五步：Rerank 精排

入口：`DashScopeRerankService.rerank()`

把 RRF 融合后的 20 条候选，和用户原始查询一起发给**阿里的 gte-rerank 模型**。

这个模型使用**交叉编码器（Cross-Encoder）**，把 `[query, document]` 拼接在一起输入 Transformer，直接输出一个 0~1 的相关性分数，精度远高于前面的粗排。

最终返回 Top 5 给 AI Agent 使用。

---

## 六、核心代码文件导航

| 文件 | 职责 |
|-----|------|
| `rag/DocumentLoader.java` | 加载 classpath:document/ 下的 Markdown 文件 |
| `rag/MarkdownStructureSplitter.java` | 按标题层级切分 + TokenTextSplitter 二级切分 |
| `rag/MyKeywordEnricher.java` | 调用大模型提取关键词，拼到正文前面 |
| `rag/DocumentIndexService.java` | **索引构建总入口**，@PostConstruct 启动时执行，双写 pgvector + ES |
| `rag/es/KnowledgeDocument.java` | ES 实体类，定义索引名、字段类型、分词器 |
| `rag/es/KnowledgeDocumentRepository.java` | Spring Data ES Repository，提供 saveAll 等 CRUD |
| `rag/es/EsDocumentService.java` | **ES 读写核心**，indexDocuments() 写入，searchByBM25() 检索 |
| `rag/HybridSearchService.java` | **多路召回 + RRF 融合**，并行调用 pgvector 和 ES |
| `rag/DashScopeRerankService.java` | 调用阿里 Rerank API 做精排 |
| `rag/QueryRewriter.java` | 查询重写（消歧、补全上下文） |
| `tools/KnowledgeBaseQueryTool.java` | 封装为 Agent Tool，串联 HybridSearch + Rerank |
| `resources/es/knowledge-document-settings.json` | ES 索引的 Settings（分片数、分词器配置） |

---

## 七、一句话总结

**写入**：Markdown → 按标题切 chunk → AI 提关键词 → 转成 KnowledgeDocument → `saveAll()` 写入 ES，IK 分词器自动拆词建立倒排索引。

**检索**：用户查询 → IK smart 分词 → `multi_match` 在 content/keywords/headerContext 三个字段加权搜索 → BM25 算法打分排序 → 返回 Top K → 与向量召回结果 RRF 融合 → Rerank 精排 → 最终 Top 5。
# 会话记忆模块

## 简历描述

> 基于 Redis 实现会话记忆持久化，解决服务重启上下文丢失问题；针对会话上下文膨胀的问题，设计 Token 感知的阈值压缩机制，超阈值时动态计算切割点并预留缓冲区避免频繁触发，通过 LLM 将早期历史压缩为摘要并原子回写 Redis。

---

## 一、整体架构

```
用户请求
  │
  ▼
ReadOnlyMemoryAdvisor（请求前：从 Redis 读取最近 20 条历史，注入 messages 头部，不回写）
  │
  ▼
ChatClient → LLM 调用（ReAct 循环 / 直答 / RAG）
  │
  ▼
BaseAgent.persistEssentialMemory()（响应后：只提取"用户问题 + 最终回答"精华）
  │
  ▼
MemoryCompressor.addWithCompaction()（写入前：Token 预算检查 → 超预算则压缩早期历史 → 原子回写 Redis）
```

**核心设计决策：读写分离**

- **读取**：通过 `ReadOnlyMemoryAdvisor`（Advisor 链路，order=-2000）在请求前注入历史，纯只读
- **写入**：由 `BaseAgent` 在意图处理结束后手动调用，只持久化精华，不存 ReAct 中间过程（工具调用、思考链等）
- **压缩**：在写入时由 `MemoryCompressor` 拦截，检查是否需要压缩

**为什么不用 Spring AI 自带的 MessageChatMemoryAdvisor？**
Spring AI 默认的 `MessageChatMemoryAdvisor` 会在 LLM 响应后自动回写所有消息，包括 ReAct 循环中的工具调用和中间推理。这会导致 Redis 中的持久化记忆被中间过程污染、Token 快速膨胀。改为读写分离后，只有"用户问题 + 最终回答"的精华被持久化，从源头控制了记忆质量。

---

## 二、RedisChatMemory —— 持久化存储层

**文件**: `chatmemory/RedisChatMemory.java`

### 解决的问题

Spring AI 内置的 `InMemoryChatMemory` 在服务重启后上下文全部丢失，无法支持生产环境的多轮对话。

### 核心设计

| 设计点 | 实现方式 | 设计理由 |
|--------|----------|----------|
| 数据结构 | Redis List（RPUSH 追加，LRANGE 读取） | 天然有序，支持按时间顺序存取对话 |
| 原子操作 | Lua 脚本封装 RPUSH + LTRIM + EXPIRE | 避免多步操作间的并发竞态窗口 |
| TTL 续期 | 每次 get/add 操作都刷新 EXPIRE | 活跃会话持续保留，僵尸会话自动过期 |
| 序列化 | 内部 MessageDTO record 桥接 Jackson | 保存 type（USER/ASSISTANT/SYSTEM）+ content |
| 存储级替换 | `replace()` 方法（DEL + RPUSH + EXPIRE） | 压缩后原子替换整个历史，不留中间态 |
| 降级兜底 | Lua 脚本执行失败 → 降级为普通 Redis 命令 | 保证核心功能可用 |

### Lua 原子脚本

```java
// add：RPUSH + LTRIM + EXPIRE，保证追加、裁剪、续期一次完成
private static final String ADD_TRIM_EXPIRE_LUA = """
        local key = KEYS[1]
        local argCount = #ARGV
        local ttlSeconds = tonumber(ARGV[argCount])
        local maxMsg = tonumber(ARGV[argCount - 1])
        for i = 1, argCount - 2 do
            redis.call('RPUSH', key, ARGV[i])
        end
        redis.call('LTRIM', key, -maxMsg, -1)
        redis.call('EXPIRE', key, ttlSeconds)
        return 1
        """;

// replace：DEL + RPUSH + EXPIRE，压缩后原子替换整个历史
private static final String REPLACE_LUA = """
        local key = KEYS[1]
        local argCount = #ARGV
        local ttlSeconds = tonumber(ARGV[argCount])
        redis.call('DEL', key)
        for i = 1, argCount - 1 do
            redis.call('RPUSH', key, ARGV[i])
        end
        redis.call('EXPIRE', key, ttlSeconds)
        return 1
        """;
```

**为什么用 Lua 脚本而不是 Redis 事务（MULTI/EXEC）？**
Redis 事务不支持在事务内读取中间结果做条件判断（CAS），且 WATCH 乐观锁在高并发下会频繁重试。Lua 脚本在 Redis 单线程内原子执行，既保证原子性又避免多次网络往返。

### 配置

```yaml
chat:
  memory:
    redis:
      key-prefix: "chat:memory:"
      ttl-hours: 24
      max-messages: 100
```

---

## 三、MemoryCompressor —— Token 感知的阈值压缩

**文件**: `chatmemory/MemoryCompressor.java`

### 解决的问题

随着对话轮次增加，Redis 中持久化的历史消息会持续膨胀。每次请求通过 ReadOnlyMemoryAdvisor 注入全量历史后，会占据主模型上下文窗口中大量空间，挤压当前推理的 token 预算，甚至触发模型 context length 上限。

### 核心参数

| 参数 | 值 | 推算依据 |
|------|-----|----------|
| MAX_MEMORY_TOKENS | 8000 | 每轮精华约 400-800 token × 10 轮 ≈ 8000 |
| TOKEN_PER_CHAR | 1.5 | 中文 1 字 ≈ 1-2 token，取偏高值保守估算 |
| COMPRESS_BUFFER_RATIO | 0.3 | 压缩后额外多压 30%，避免阈值边缘频繁触发 |
| SINGLE_MESSAGE_TRUNCATE | 500 | 送入 LLM 摘要前截断单条过长消息，控制摘要 prompt 长度 |
| COMPRESS_MODEL | qwen-turbo | 轻量模型，降低压缩操作的延迟和成本 |

### 压缩流程

```
addWithCompaction(conversationId, newMessages)
  │
  ├─ 1. 从 Redis 读取现有历史（最多 100 条）
  │
  ├─ 2. 合并：existingHistory + newMessages → 估算总 token
  │
  ├─ 3. totalTokens ≤ 8000？
  │     └─ YES → redisChatMemory.add()，直接追加，结束
  │
  └─ 4. totalTokens > 8000，触发压缩：
        │
        ├─ a. 计算超出量：excess = totalTokens - 8000
        ├─ b. 加缓冲：cutTarget = excess + 8000 × 0.3 = excess + 2400
        ├─ c. 从最早消息逐条累加 token，找到切割点 cutIndex
        ├─ d. earlyMessages [0, cutIndex) → LLM 压缩为摘要
        ├─ e. 构建新列表：[摘要 SystemMessage] + [cutIndex, end) 保留的历史 + newMessages
        └─ f. redisChatMemory.replace() 原子回写
```

### 动态切割点计算

```java
private int findCutIndex(List<Message> messages, int excess) {
    int accumulated = 0;
    for (int i = 0; i < messages.size() - 1; i++) {
        String text = messages.get(i).getText();
        accumulated += (int) ((text != null ? text.length() : 0) * TOKEN_PER_CHAR);
        if (accumulated >= excess) {
            return i + 1;  // 刚好覆盖需要压缩的量
        }
    }
    return Math.max(1, messages.size() - 1);  // 兜底：至少保留最后 1 条
}
```

### 缓冲区机制

```java
int excess = totalTokens - MAX_MEMORY_TOKENS;       // 超出量
int buffer = (int) (MAX_MEMORY_TOKENS * COMPRESS_BUFFER_RATIO); // 8000 × 0.3 = 2400
int cutTarget = excess + buffer;
// 压缩后剩余约 8000 - 2400 = 5600 token，可承受约 3-5 轮新增对话
// 避免每新增一轮就触发压缩
```

### LLM 摘要生成

```java
private String summarize(List<Message> messages) {
    // 1. 拼接对话历史，每条消息截断到 500 字符
    // 2. 构造 prompt 要求保留：用户问题、助手结论、关键上下文
    // 3. 使用 qwen-turbo（temperature=0.0）生成摘要
    // 4. 失败则降级为 buildFallbackSummary()：取每条消息前 100 字拼接
}
```

### 降级策略

| 故障场景 | 降级行为 |
|---------|---------|
| LLM 摘要生成失败（网络异常、模型超时等） | `buildFallbackSummary()`：截取每条消息前 100 字拼接 |
| Redis 回写失败 | 仅打印 warn 日志，不影响当前请求（当前 ReAct 内存中已有完整上下文） |
| Lua 脚本执行失败 | 降级为普通 Redis 命令序列（add 操作的兜底逻辑） |

---

## 四、精华写入机制（BaseAgent）

**文件**: `agent/BaseAgent.java`

```java
private void persistEssentialMemory(String userQuestion, String finalAnswer) {
    List<Message> essential = List.of(
            new UserMessage(userQuestion),
            new AssistantMessage(finalAnswer != null ? finalAnswer : "")
    );
    // 通过 MemoryCompressor 写入，自动检查 Token 预算
    memoryCompressor.addWithCompaction(conversationId, essential);
}
```

**调用时机**：在 `handleChatIntent()`（闲聊直答）、`handleKnowledgeIntent()`（RAG 直答）、`run()` 循环结束（ReAct 完成）三个出口统一调用。

**为什么只存精华不存全量？**
ReAct 循环中一轮完整推理可能产生 5-10 条中间消息（思考、工具调用请求、工具返回值等），每轮约 2000-5000 token。如果全量持久化，3-5 轮交互后就会撑爆 token 预算。只存"用户问题 + 最终回答"，每轮约 400-800 token，10 轮才触及压缩阈值。

---

## 五、ReadOnlyMemoryAdvisor —— 读取注入层

**文件**: `advisor/ReadOnlyMemoryAdvisor.java`

```java
public class ReadOnlyMemoryAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {
    private static final int DEFAULT_LAST_N = 20;

    @Override
    public int getOrder() { return -2000; } // 最高优先级，确保历史在其他 Advisor 之前注入

    private AdvisedRequest injectHistory(AdvisedRequest request) {
        List<Message> history = redisChatMemory.get(conversationId, lastN);
        // 将历史消息放在当前 messages 前面
        List<Message> merged = new ArrayList<>(history);
        merged.addAll(request.messages());
        return AdvisedRequest.from(request).messages(merged).build();
    }
}
```

**为什么 order = -2000？**
Spring AI Advisor 链按 order 升序执行。设为 -2000 保证它在日志 Advisor、业务 Advisor 之前执行，使后续 Advisor 看到的 messages 已包含完整历史上下文。

---

## 六、集成方式（YuManus）

```java
public YuManus(..., RedisChatMemory redisChatMemory, MemoryCompressor memoryCompressor, ...) {
    // 注入压缩器，供 BaseAgent.persistEssentialMemory 使用
    this.setChatMemory(redisChatMemory);
    this.setMemoryCompressor(memoryCompressor);

    // Advisor 链路：只读记忆注入 + 日志
    ReadOnlyMemoryAdvisor readOnlyMemoryAdvisor =
            new ReadOnlyMemoryAdvisor(redisChatMemory, conversationId);

    ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
            .defaultAdvisors(readOnlyMemoryAdvisor, new MyLoggerAdvisor())
            .build();
    this.setChatClient(chatClient);
}
```

---

## 七、设计亮点总结

1. **读写分离**：读取通过 Advisor 自动注入，写入由 Agent 手动控制只存精华，避免 ReAct 中间过程污染持久化记忆
2. **Lua 原子性**：add/replace 操作均通过 Lua 脚本保证原子执行，消除并发竞态
3. **缓冲区机制**：压缩时额外多压 30%（2400 token），压缩后剩余约 5600 token 可承受 3-5 轮新增，避免阈值边缘频繁触发
4. **动态切割**：切割点根据实际超出量逐条累加计算，不是固定砍一半，精准释放刚好足够的空间
5. **多层降级**：LLM 摘要失败 → 文本截断兜底；Lua 失败 → 普通命令降级；Redis 写入失败 → 不影响当前请求
6. **存储级压缩**：压缩结果直接回写 Redis，服务重启后加载的就是压缩后的精简历史

---

## 八、大厂面试 Q&A

> 以下问题均从简历描述出发（面试官看不到代码），回答结合实际代码实现。

---

### Q1：你说"基于 Redis 实现会话记忆持久化"，为什么选 Redis？用什么数据结构存的？

**A**：选 Redis 主要考虑三点：

- **低延迟**：对话系统对响应速度敏感，Redis 单次读写微秒级，不会成为瓶颈
- **原生 TTL**：对话有天然的生命周期，不活跃的会话可以自动过期清理，不需要额外的定时清理任务
- **部署简单**：项目已经在用 Redis 做缓存，不需要引入新的中间件

数据结构用的 **Redis List**：
- 对话天然是时间有序的消息序列，List 的 RPUSH 追加 + LRANGE 读取刚好匹配这个语义
- 用 `LTRIM` 控制最大条数上限（100 条），防止单个会话无限膨胀
- 序列化方式是用一个内部的 `MessageDTO` record，存储消息类型（USER/ASSISTANT/SYSTEM）和内容，通过 Jackson 序列化为 JSON

---

### Q2：你提到原子回写 Redis，具体怎么保证原子性的？为什么不用 Redis 事务？

**A**：通过 **Lua 脚本** 保证原子性，有两个脚本：

- **add 脚本**：`RPUSH + LTRIM + EXPIRE` 一次执行，保证消息追加、长度裁剪、TTL 续期三步不会被其他操作插入
- **replace 脚本**：`DEL + RPUSH + EXPIRE` 一次执行，压缩后整体替换历史，不会出现"删完旧数据还没写入新数据"的中间态

不用 Redis 事务（MULTI/EXEC）的原因：
- Redis 事务不支持在事务内读取中间结果做条件判断，灵活性不够
- WATCH 乐观锁在高并发下会频繁 CAS 重试
- Lua 脚本在 Redis 单线程内原子执行，而且只需要一次网络往返，性能更优

同时做了降级：如果 Lua 脚本执行失败（比如 Redis 版本不支持），会降级为普通的 Redis 命令序列来执行。

---

### Q3：你说"Token 感知的压缩机制"，怎么估算 Token 的？为什么不用真实的 Tokenizer？

**A**：用的是**字符数 × 系数**的近似估算方式。中文场景下 1 个汉字大约对应 1-2 个 token，我取了偏高的 1.5 作为系数。

没用真实 Tokenizer 的原因：
- **引入 Tokenizer 有额外开销**：调用 Tokenizer 需要加载词表、做分词计算，每次压缩检查都调用不划算
- **压缩决策只需要量级正确**：不需要精确到个位数，只要能正确判断"是否超预算"和"大概需要压缩多少"就够了
- **偏高系数 + 缓冲区可以容错**：TOKEN_PER_CHAR 取 1.5（偏高）意味着会提前触发压缩，加上 30% 的缓冲区，实际估算的误差完全在容忍范围内

---

### Q4：Token 阈值为什么设 8000？这个数是怎么推算出来的？

**A**：推算逻辑是这样的：

1. **单轮精华的大小**：我只持久化"用户原始问题 + 最终回答"的精华，不存 ReAct 循环中的工具调用和中间推理过程。这样每轮大约 400-800 token
2. **保留轮次的需求**：保留最近 10 轮对话上下文通常足够覆盖一个完整的小任务。10 轮 × 800 token ≈ 8000
3. **与主模型上下文窗口的比例**：通义千问系列的上下文窗口在 8K-128K 之间，8000 token 的历史记忆只占一小部分，给当前轮的 system prompt、工具描述、当前问题留了充足空间

如果全量持久化（包括 ReAct 中间过程），每轮可能产生 2000-5000 token，3-5 轮就撑爆预算。**"只存精华"的决策使得 8000 这个阈值变得合理。**

---

### Q5：你说"动态计算切割点并预留缓冲区"，具体怎么做的？

**A**：分两步：

**第一步，计算切割目标量**：
- `excess = totalTokens - 8000`（超出量）
- `buffer = 8000 × 0.3 = 2400`（缓冲区）
- `cutTarget = excess + buffer`

缓冲区的意义是：不只压缩刚好超出的量，而是额外多压 30%。压缩后剩余约 5600 token，可以承受后续 3-5 轮新增对话才再次触发。如果没有缓冲区，每新增一轮就可能触发压缩，频繁调用 LLM 摘要既浪费成本又增加延迟。

**第二步，逐条累加找切割位**：
从最早的消息开始逐条累加 token，直到累加量 ≥ cutTarget，这个位置就是切割点。切割点之前的消息送入 LLM 压缩为摘要，之后的消息保留原文。

这样切割是**动态的**——超出得多就多压，超出得少就少压，不是固定砍一半。

---

### Q6：LLM 压缩摘要如果失败了怎么办？压缩这个操作本身有没有可能影响正常请求？

**A**：做了**两层降级**：

1. **LLM 摘要失败**（网络超时、模型异常等）→ 降级为 `buildFallbackSummary()`：对每条消息截取前 100 个字符拼接，不调用 LLM，保证至少有个粗略的摘要
2. **Redis 回写失败** → 仅打印 warn 日志，不抛异常。因为当前请求的工作记忆（messageList）完全在内存中，不依赖 Redis 写入结果，所以写入失败不影响本次请求的正常响应

至于对正常请求的影响：压缩操作是在 `persistEssentialMemory()` 中触发的，这是在 Agent 已经生成最终回答之后才调用的。即使压缩耗时较长，也不会阻塞"思考和回答"这个核心路径——用户已经拿到了回答，压缩是在响应之后做的收尾工作。

摘要用的 qwen-turbo 是轻量模型，temperature 设为 0.0 保证摘要的确定性，送入的每条消息也截断到 500 字符来控制 prompt 长度和耗时。

---

### Q7：你说只持久化"用户问题+最终回答"精华，为什么不存完整的对话过程？会不会丢失重要信息？

**A**：不存完整过程是因为 **ReAct 循环的中间过程持久化弊大于利**：

- **噪声过多**：一轮 ReAct 推理可能包含 5-10 条中间消息（思考步骤、工具调用请求、工具返回值），大部分是给当前推理用的，对下次对话没有上下文价值
- **膨胀剧烈**：中间过程单轮可达 2000-5000 token，全量持久化 3-5 轮就超预算，会把压缩机制逼到极限
- **下轮注入无用**：下一次对话把这些工具调用和思考链注入进去，反而会干扰新一轮的推理

只存精华不会丢失重要信息——用户关心的是"我问了什么、你答了什么"，而不是"你中间怎么思考的、调了哪些工具"。后者是过程性信息，精华已经包含了结论。

补充一个设计细节：读取注入时取最近 20 条消息（DEFAULT_LAST_N = 20），而这 20 条全部是精华（一轮 2 条），相当于注入最近 10 轮对话的完整问答结论，信息密度远高于注入全量中间过程。

---

### Q8：如果两个请求并发来了，压缩会有竞态问题吗？

**A**：有潜在竞态，但影响可控：

- **同一个 conversationId 的并发**：在 `addWithCompaction()` 中先 `get` 读历史，再判断是否压缩，再 `replace` 写回。如果两个请求并发执行，可能都读到相同的历史，各自压缩后分别 replace。由于 replace 用的是 Lua 脚本原子操作（DEL + RPUSH + EXPIRE），不会出现脏数据，但后写入的会覆盖先写入的结果
- **实际场景下影响很小**：同一个用户的对话通常是串行的（问一句答一句），真正的并发概率很低。即使发生覆盖，后果也只是丢了一轮精华，不会导致数据损坏或服务异常

如果要严格解决，可以考虑：
- 对 conversationId 加分布式锁（Redisson）
- 或者用 Redis 的 WATCH 乐观锁做 CAS 重试

但在当前业务场景下，增加的复杂度不值得。

---

### Q9：压缩摘要本身也占 Token，有没有可能压缩完了还是超预算？

**A**：理论上有可能，但通过两个机制保证实际不会出现问题：

1. **30% 的缓冲区**：压缩目标是 cutTarget = excess + 2400，实际释放的空间远大于刚好超出的量。即使摘要比预期长，也有 2400 token 的余量兜底
2. **摘要天然比原文短得多**：被压缩的是多轮完整对话（可能几千 token），而 LLM 生成的摘要通常只有 200-500 字符。从信息论角度，压缩比在 5:1 到 10:1 之间

如果极端情况下真的超了也没关系——下一轮写入时会再次触发压缩，形成**自修复**。不会出现无限递归（每次压缩都会减少消息条数）。

---

### Q10：ReadOnlyMemoryAdvisor 里为什么 DEFAULT_LAST_N 设为 20，和 MAX_MEMORY_TOKENS=8000 是什么关系？

**A**：

- `DEFAULT_LAST_N = 20`：控制从 Redis 读取的消息条数上限。每轮精华 2 条（UserMessage + AssistantMessage），20 条就是最近 10 轮的完整问答
- `MAX_MEMORY_TOKENS = 8000`：控制 Redis 中持久化的总 token 量上限

它们的关系是**互相配合**：
- 8000 token 的预算恰好能容纳约 10 轮精华（每轮 400-800 token）
- 20 条消息恰好能覆盖这 10 轮精华

如果 Redis 里发生了压缩，存储的消息会变少（早期多轮被压缩为 1 条摘要），这时 LAST_N=20 能把压缩后的所有消息全部读出来，包括摘要 + 保留的近期历史。

---

### Q11：为什么用 Redis List 而不是 String（存 JSON 数组）或 Hash？

**A**：

- **vs String 存 JSON 数组**：List 支持 RPUSH 增量追加，不需要每次读取-反序列化-追加-序列化-全量写回。对于高频的对话追加操作更高效。而且 `LTRIM` 可以直接在 Redis 侧裁剪，不需要客户端做截断
- **vs Hash**：对话消息是有序序列，而 Hash 是 KV 无序映射，语义不匹配。如果用 Hash 需要额外维护索引来保证顺序，增加了复杂度

List 唯一的劣势是不支持随机更新（比如修改第 3 条消息），但对话记忆不需要这个能力——只有追加和整体替换。

---

### Q12：你提到 TTL 24 小时，为什么是 24 小时？会话跨天了怎么办？

**A**：

24 小时是个平衡点：
- **太短**（比如 1 小时）：用户稍微暂停就丢上下文，体验差
- **太长**（比如 7 天）：不活跃会话占用 Redis 内存，堆积过多

关键设计是 **TTL 续期**：每次 `get`（读取历史）和 `add`（写入精华）操作都会刷新 EXPIRE。只要用户持续对话，TTL 就会不断续期，永远不会过期。24 小时实际是"最后一次交互后 24 小时才过期"，而不是"会话创建后 24 小时过期"。

所以跨天不是问题——只有用户真的停止互动超过 24 小时，上下文才会被清理。

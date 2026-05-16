package com.yupi.yuaiagent.chatmemory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Redis 的对话记忆实现
 * <p>
 * 设计要点：
 * <ul>
 *     <li>使用 List 结构存储消息，<code>RPUSH</code> 追加、<code>LRANGE</code> 取最近 N 条</li>
 *     <li>add + trim + expire 通过 Lua 脚本原子执行，避免并发竞态</li>
 *     <li>每次访问续期 TTL，活跃会话自动保留，僵尸会话自动过期清理</li>
 *     <li>Message 通过 MessageDTO 桥接 Jackson 序列化，避免接口类型丢失</li>
 *     <li>提供 replace 方法支持存储级压缩：原子性全量替换消息列表</li>
 * </ul>
 */
@Component
@Slf4j
public class RedisChatMemory implements ChatMemory {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private final String keyPrefix;
    private final Duration ttl;
    private final int maxMessages;

    /**
     * Lua 脚本：原子执行 RPUSH + LTRIM + EXPIRE，消除 add+trim 竞态窗口。
     * <p>
     * KEYS[1] = list key
     * ARGV[1..N-2] = 序列化后的消息 JSON
     * ARGV[N-1]   = maxMessages (负偏移的保留条数)
     * ARGV[N]     = ttl (秒)
     */
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

    /**
     * Lua 脚本：原子执行 DEL + RPUSH + EXPIRE，用于存储级全量替换。
     * <p>
     * KEYS[1] = list key
     * ARGV[1..N-1] = 序列化后的消息 JSON
     * ARGV[N]      = ttl (秒)
     */
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

    private final DefaultRedisScript<Long> addTrimExpireScript;
    private final DefaultRedisScript<Long> replaceScript;

    public RedisChatMemory(StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper,
                           @Value("${chat.memory.redis.key-prefix:chat:memory:}") String keyPrefix,
                           @Value("${chat.memory.redis.ttl-hours:24}") long ttlHours,
                           @Value("${chat.memory.redis.max-messages:100}") int maxMessages) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix;
        this.ttl = Duration.ofHours(ttlHours);
        this.maxMessages = maxMessages;

        this.addTrimExpireScript = new DefaultRedisScript<>(ADD_TRIM_EXPIRE_LUA, Long.class);
        this.replaceScript = new DefaultRedisScript<>(REPLACE_LUA, Long.class);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        List<String> serialized = serializeMessages(messages);
        if (serialized.isEmpty()) {
            return;
        }
        String key = buildKey(conversationId);

        // 通过 Lua 脚本原子执行 RPUSH + LTRIM + EXPIRE
        List<String> args = new ArrayList<>(serialized.size() + 2);
        args.addAll(serialized);
        args.add(String.valueOf(maxMessages));
        args.add(String.valueOf(ttl.toSeconds()));

        try {
            redisTemplate.execute(addTrimExpireScript, List.of(key), args.toArray(new String[0]));
        } catch (Exception e) {
            log.error("[RedisChatMemory] Lua 脚本执行失败（add），降级为普通操作：{}", e.getMessage());
            redisTemplate.opsForList().rightPushAll(key, serialized);
            redisTemplate.opsForList().trim(key, -maxMessages, -1);
            redisTemplate.expire(key, ttl);
        }
    }

    /**
     * 原子性全量替换消息列表（存储级压缩专用）。
     * <p>
     * 通过 Lua 脚本 DEL + RPUSH + EXPIRE 原子执行，
     * 确保替换过程中不会被其他并发写入插入脏数据。
     *
     * @param conversationId 会话 ID
     * @param messages       压缩后的完整消息列表
     */
    public void replace(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            clear(conversationId);
            return;
        }
        List<String> serialized = serializeMessages(messages);
        if (serialized.isEmpty()) {
            return;
        }
        String key = buildKey(conversationId);

        List<String> args = new ArrayList<>(serialized.size() + 1);
        args.addAll(serialized);
        args.add(String.valueOf(ttl.toSeconds()));

        try {
            redisTemplate.execute(replaceScript, List.of(key), args.toArray(new String[0]));
            log.info("[RedisChatMemory] 存储级替换完成，conversationId={}，消息数={}", conversationId, messages.size());
        } catch (Exception e) {
            log.error("[RedisChatMemory] Lua 脚本执行失败（replace），降级为普通操作：{}", e.getMessage());
            redisTemplate.delete(key);
            redisTemplate.opsForList().rightPushAll(key, serialized);
            redisTemplate.expire(key, ttl);
        }
    }

    /**
     * 将 Message 列表序列化为 JSON 字符串列表，跳过序列化失败的消息。
     */
    private List<String> serializeMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> serialized = new ArrayList<>(messages.size());
        for (Message message : messages) {
            try {
                serialized.add(objectMapper.writeValueAsString(MessageDTO.from(message)));
            } catch (JsonProcessingException e) {
                log.warn("[RedisChatMemory] 消息序列化失败，已跳过：{}", e.getMessage());
            }
        }
        return serialized;
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        if (lastN <= 0) {
            return Collections.emptyList();
        }
        String key = buildKey(conversationId);
        List<String> raw = redisTemplate.opsForList().range(key, -lastN, -1);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        // 读取也续期，保持活跃会话不过期
        redisTemplate.expire(key, ttl);

        List<Message> result = new ArrayList<>(raw.size());
        for (String json : raw) {
            try {
                MessageDTO dto = objectMapper.readValue(json, MessageDTO.class);
                Message message = dto.toMessage();
                if (message != null) {
                    result.add(message);
                }
            } catch (JsonProcessingException e) {
                log.warn("[RedisChatMemory] 消息反序列化失败，已跳过：{}", e.getMessage());
            }
        }
        return result;
    }

    @Override
    public void clear(String conversationId) {
        redisTemplate.delete(buildKey(conversationId));
    }

    private String buildKey(String conversationId) {
        return keyPrefix + conversationId;
    }

    /**
     * Message 序列化中转 DTO，保留消息类型与必要字段。
     * 工具消息暂仅保留文本，避免破坏序列化复杂度；多数场景下检索历史以 USER/ASSISTANT 为主。
     */
    private record MessageDTO(@JsonProperty("type") String type,
                              @JsonProperty("content") String content) {

        @JsonCreator
        MessageDTO {
        }

        static MessageDTO from(Message message) {
            return new MessageDTO(message.getMessageType().name(), safeText(message));
        }

        Message toMessage() {
            if (type == null) {
                return null;
            }
            MessageType messageType;
            try {
                messageType = MessageType.valueOf(type);
            } catch (IllegalArgumentException e) {
                return null;
            }
            String text = content == null ? "" : content;
            return switch (messageType) {
                case USER -> new UserMessage(text);
                case ASSISTANT -> new AssistantMessage(text);
                case SYSTEM -> new SystemMessage(text);
                case TOOL -> new ToolResponseMessage(Collections.emptyList());
            };
        }

        private static String safeText(Message message) {
            String text = message.getText();
            return text == null ? "" : text;
        }
    }
}
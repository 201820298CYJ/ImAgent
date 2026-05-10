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
 *     <li>每次写入后通过 <code>LTRIM</code> 限制最大长度，防止单会话无限增长</li>
 *     <li>每次访问续期 TTL，活跃会话自动保留，僵尸会话自动过期清理</li>
 *     <li>Message 通过 MessageDTO 桥接 Jackson 序列化，避免接口类型丢失</li>
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
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String key = buildKey(conversationId);
        List<String> serialized = new ArrayList<>(messages.size());
        for (Message message : messages) {
            try {
                serialized.add(objectMapper.writeValueAsString(MessageDTO.from(message)));
            } catch (JsonProcessingException e) {
                log.warn("[RedisChatMemory] 消息序列化失败，已跳过：{}", e.getMessage());
            }
        }
        if (serialized.isEmpty()) {
            return;
        }
        redisTemplate.opsForList().rightPushAll(key, serialized);
        // 仅保留最近 maxMessages 条
        redisTemplate.opsForList().trim(key, -maxMessages, -1);
        // 续期 TTL
        redisTemplate.expire(key, ttl);
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
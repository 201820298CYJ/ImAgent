package com.yupi.yuaiagent.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.stereotype.Component;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 查询重写器
 */
@Component
public class QueryRewriter {

    private final ChatClient chatClient;

    public QueryRewriter(ChatModel dashscopeChatModel) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
    }

    /**
     * 执行查询重写（支持上下文感知）
     *
     * @param prompt
     * @param chatMemory
     * @param chatId
     * @return
     */
    public String doQueryRewrite(String prompt, ChatMemory chatMemory, String chatId) {
        // 1. 获取对话历史
        List<Message> history = chatMemory.get(chatId, 10);
        
        // 2. 如果没有历史，直接返回原 Prompt（或者简单的重写）
        if (history.isEmpty()) {
            return prompt;
        }

        // 3. 构造重写 Prompt
        String historyText = history.stream()
                .map(msg -> msg.getMessageType() + ": " + msg.getText())
                .collect(Collectors.joining("\n"));

        String rewritePrompt = String.format("""
            根据以下对话历史和用户当前的问题，请重写一个专门用于搜索引擎检索的、独立且完整的查询语句。
            要求：
            1. 消除指代歧义（如“它”、“他”、“那个”）。
            2. 补全隐含的上下文背景（如学校名称、专业名称）。
            3. 只输出重写后的一句话结果，不要解释。
            
            对话历史：
            %s
            
            用户当前问题：
            %s
            """, historyText, prompt);

        // 4. 执行重写
        return chatClient.prompt(rewritePrompt).call().content();
    }
}

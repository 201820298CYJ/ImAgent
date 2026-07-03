package com.yupi.yuagenttoolsmcpserver;

import com.yupi.yuagenttoolsmcpserver.tools.WebScrapingTool;
import com.yupi.yuagenttoolsmcpserver.tools.WebSearchTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class YuAgentToolsMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(YuAgentToolsMcpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider agentTools(WebSearchTool webSearchTool,
                                           WebScrapingTool webScrapingTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(webSearchTool, webScrapingTool)
                .build();
    }
}

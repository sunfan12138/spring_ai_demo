package com.ifoodbuy.spring_ai_demo.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 聊天记忆配置类
 * 配置基于 MySQL 的会话记忆存储和 MCP 工具集成
 * <p>
 * 注意：
 * 1. 使用 spring-ai-starter-model-chat-memory-repository-jdbc 后，
 *    Spring AI 会自动配置 JdbcChatMemoryRepository，可以直接注入使用
 * 2. 使用 spring-ai-starter-mcp-client 后，Spring AI 会根据 application.yaml 中的配置
 *    自动创建 MCP 客户端，并将 MCP 工具注册为 ToolCallback Bean
 */
@Configuration
public class ChatMemoryConfig {

    /**
     * 创建聊天记忆实例
     * 使用 MessageWindowChatMemory 来管理会话记忆窗口
     * <p>
     * Spring AI 会自动配置 JdbcChatMemoryRepository，直接注入使用即可
     * 
     * @param jdbcChatMemoryRepository Spring AI 自动配置的 JdbcChatMemoryRepository
     * @return ChatMemory 实例
     */
    @Bean
    public ChatMemory chatMemory(JdbcChatMemoryRepository jdbcChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(20) // 保留最近 20 条消息
                .build();
    }

}

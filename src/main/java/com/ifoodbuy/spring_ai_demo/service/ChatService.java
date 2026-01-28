package com.ifoodbuy.spring_ai_demo.service;

import com.ifoodbuy.spring_ai_demo.dto.ChatRequest;
import com.ifoodbuy.spring_ai_demo.dto.ChatResponse;
import com.ifoodbuy.spring_ai_demo.repository.ConversationMetadataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

/**
 * 聊天服务类
 * 处理对话逻辑，集成会话记忆功能
 * 
 * 使用 MessageChatMemoryAdvisor 自动管理历史消息：
 * - 自动从记忆中检索对话历史
 * - 自动保存新的对话消息
 */
@Slf4j
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ConversationMetadataRepository metadataRepository;
    private final RagService ragService; // 可选，如果 RAG 服务存在则使用

    public ChatService(
            ChatClient chatClient,
            ConversationMetadataRepository metadataRepository,
            org.springframework.beans.factory.ObjectProvider<RagService> ragServiceProvider) {
        this.chatClient = chatClient;
        this.metadataRepository = metadataRepository;
        this.ragService = ragServiceProvider.getIfAvailable();
    }

    /**
     * 处理聊天请求（非流式）
     * 
     * ChatClient 已配置 MCP 工具，当用户询问需要搜索的问题时，
     * AI 会自动调用 OpenWebSearch MCP 工具进行网络搜索
     * 
     * @param request 聊天请求
     * @return 聊天响应
     */
    public ChatResponse chat(ChatRequest request) {
        String conversationId = request.getConversationId();
        String userMessage = request.getMessage();
        boolean useRag = request.isUseRag() && ragService != null; // 如果请求启用 RAG 且服务可用

        try {
            String responseContent;
            
            if (useRag) {
                // 使用 RAG（检索增强生成）
                log.info("使用 RAG 模式回答用户问题");
                responseContent = ragService.answerWithRag(userMessage, conversationId);
            } else {
                // 使用 ChatClient 调用 AI 模型
                // MessageChatMemoryAdvisor 会自动：
                // 1. 从记忆中检索该会话的历史消息
                // 2. 将历史消息添加到 prompt 中
                // 3. 在调用完成后自动保存新的对话消息
                // 
                // MCP 工具会自动调用：
                // 当 AI 识别到需要搜索的问题时，会自动调用 OpenWebSearch MCP 工具
                responseContent = chatClient.prompt()
                        .user(userMessage)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .call()
                        .content();
            }

            // 更新对话的最后更新时间
            updateConversationTimestamp(conversationId);

            // 返回响应
            return new ChatResponse(
                    responseContent,
                    conversationId
            );
        } catch (Exception e) {
            // 捕获所有异常，打印失败原因到日志，并返回固定错误消息
            log.error("处理聊天请求失败 - 用户消息: {}, 失败原因: {}", userMessage, e.getMessage(), e);
            
            return new ChatResponse(
                    "抱歉，处理请求时出现错误。请重新尝试，或者换一种方式提问。",
                    conversationId
            );
        }
    }

    /**
     * 处理聊天请求（流式响应）
     * 
     * ChatClient 已配置 MCP 工具，当用户询问需要搜索的问题时，
     * AI 会自动调用 OpenWebSearch MCP 工具进行网络搜索
     * 
     * @param request 聊天请求
     * @return 流式响应，每个元素是响应内容的一个片段（Flux）
     */
    public Flux<String> chatStream(ChatRequest request) {
        String conversationId = request.getConversationId();
        String userMessage = request.getMessage();
        boolean useRag = request.isUseRag() && ragService != null; // 如果请求启用 RAG 且服务可用

        Flux<String> responseFlux;
        
        if (useRag) {
            // 使用 RAG（检索增强生成）流式响应
            log.info("使用 RAG 模式流式回答用户问题");
            responseFlux = ragService.answerWithRagStream(userMessage, conversationId);
        } else {
            // 使用 ChatClient 流式调用 AI 模型
            // MessageChatMemoryAdvisor 会自动：
            // 1. 从记忆中检索该会话的历史消息
            // 2. 将历史消息添加到 prompt 中
            // 3. 在流式响应完成后自动保存新的对话消息
            // 
            // MCP 工具会自动调用：
            // 当 AI 识别到需要搜索的问题时，会自动调用 OpenWebSearch MCP 工具
            responseFlux = chatClient.prompt()
                    .user(userMessage)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .stream()
                    .content();
        }

        return responseFlux
                .doOnComplete(() -> {
                    // 流式响应完成后，更新对话的最后更新时间
                    updateConversationTimestamp(conversationId);
                })
                .onErrorResume(error -> {
                    // 打印失败原因到日志
                    String errorMessage = error.getMessage();
                    log.error("流式响应处理失败 - 失败原因: {}", errorMessage, error);
                    
                    // 返回固定的错误消息
                    return Flux.just("抱歉，处理请求时出现错误。请重新尝试，或者换一种方式提问。");
                });
    }

    /**
     * 更新对话的最后更新时间
     */
    private void updateConversationTimestamp(String conversationId) {
        try {
            metadataRepository.updateTimestamp(conversationId);
        } catch (Exception e) {
            // 如果metadata不存在，忽略错误（可能是新对话，还没有创建metadata）
            log.debug("更新对话时间戳失败: {}", e.getMessage());
        }
    }
}

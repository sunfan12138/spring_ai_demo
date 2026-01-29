package com.ifoodbuy.spring_ai_demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG (检索增强生成) 服务
 * 在回答用户问题时，先从知识库检索相关知识，然后结合知识生成回答
 * 需要 VectorStore Bean 存在才会创建
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final ChatClient chatClient;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 使用 RAG 回答用户问题
     *
     * @param userMessage 用户消息
     * @param conversationId 会话ID
     * @return AI 回答
     */
    public String answerWithRag(String userMessage, String conversationId) {
        log.info("使用 RAG 回答用户问题: {}", userMessage);

        // 1. 从知识库检索相关知识
        List<Document> relevantDocs = knowledgeBaseService.search(userMessage, 5, null);

        // 2. 构建包含知识的提示词
        String context = buildContext(relevantDocs);

        // 3. 使用 ChatClient 生成回答，包含检索到的知识
        String prompt = buildPrompt(userMessage, context);

        String response = chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        log.info("RAG 回答生成完成");
        return response;
    }

    /**
     * 使用 RAG 流式回答用户问题
     *
     * @param userMessage 用户消息
     * @param conversationId 会话ID
     * @return AI 回答流
     */
    public Flux<String> answerWithRagStream(String userMessage, String conversationId) {
        log.info("使用 RAG 流式回答用户问题: {}", userMessage);

        // 1. 从知识库检索相关知识
        List<Document> relevantDocs = knowledgeBaseService.search(userMessage, 5, null);

        // 2. 构建包含知识的提示词
        String context = buildContext(relevantDocs);
        String prompt = buildPrompt(userMessage, context);

        // 3. 使用 ChatClient 流式生成回答
        return chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

    /**
     * 构建上下文（从检索到的文档中提取内容）
     */
    private String buildContext(List<Document> documents) {
        if (documents.isEmpty()) {
            return "未找到相关知识。";
        }

        return documents.stream()
                .map(doc -> {
                    String content = doc.getFormattedContent();
                    Map<String, Object> metadata = doc.getMetadata();
                    String source = metadata.getOrDefault("filename", "未知来源").toString();
                    return String.format("【来源: %s】\n%s", source, content);
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * 构建包含知识的提示词
     */
    private String buildPrompt(String userMessage, String context) {
        return String.format("""
                请基于以下知识库内容回答用户的问题。如果知识库中没有相关信息，请明确说明，不要编造答案。
                
                【知识库内容】
                %s
                
                【用户问题】
                %s
                
                请基于上述知识库内容，准确、清晰地回答用户的问题。
                """, context, userMessage);
    }
}

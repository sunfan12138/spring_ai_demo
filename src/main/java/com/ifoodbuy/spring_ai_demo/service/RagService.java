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
     * @param autoMode true=由助手判断是否参考知识库，false=始终基于知识库回答
     * @return AI 回答
     */
    public String answerWithRag(String userMessage, String conversationId, boolean autoMode) {
        log.info("使用 RAG 回答用户问题: {}, autoMode={}", userMessage, autoMode);

        if (autoMode) {
            // 由助手判断：先查所有文档名称，让模型根据文档列表判断是否调用知识库
            String docList = knowledgeBaseService.getDocumentListForRag();
            if (docList == null || docList.isBlank()) {
                log.info("知识库无文档，直接回答");
                return chatClient.prompt()
                        .user(userMessage)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .call()
                        .content();
            }
            String decisionPrompt = buildDecisionPrompt(docList);
            // 先调用一次（不写入对话记忆），仅让模型判断 YES/NO
            String decision = chatClient.prompt()
                    .system(s -> s.text(decisionPrompt))
                    .user(userMessage)
                    .call()
                    .content();
            boolean useRag = decision != null && decision.trim().toUpperCase().contains("YES");
            log.info("助手判断是否使用知识库: {}", useRag);
            if (!useRag) {
                return chatClient.prompt()
                        .user(userMessage)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .call()
                        .content();
            }
        }

        // 1. 从知识库检索相关知识
        List<Document> relevantDocs = knowledgeBaseService.search(userMessage, 10, null);

        // 2. 知识库内容放到系统提示词，页面只展示用户问题和助手回复
        String context = buildContext(relevantDocs);
        String systemPrompt = buildSystemPrompt(context, false);

        // 3. 系统提示词传知识库，用户消息只传原始问题
        String response = chatClient.prompt()
                .system(s -> s.text(systemPrompt))
                .user(userMessage)
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
     * @param autoMode true=由助手判断是否参考知识库，false=始终基于知识库回答
     * @return AI 回答流
     */
    public Flux<String> answerWithRagStream(String userMessage, String conversationId, boolean autoMode) {
        log.info("使用 RAG 流式回答用户问题: {}, autoMode={}", userMessage, autoMode);

        if (autoMode) {
            // 由助手判断：先查所有文档名称，让模型根据文档列表判断是否调用知识库（先同步得到 YES/NO，再流式回答）
            String docList = knowledgeBaseService.getDocumentListForRag();
            if (docList == null || docList.isBlank()) {
                log.info("知识库无文档，直接流式回答");
                return chatClient.prompt()
                        .user(userMessage)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .stream()
                        .content();
            }
            String decisionPrompt = buildDecisionPrompt(docList);
            String decision = chatClient.prompt()
                    .system(s -> s.text(decisionPrompt))
                    .user(userMessage)
                    .call()
                    .content();
            boolean useRag = decision != null && decision.trim().toUpperCase().contains("YES");
            log.info("助手判断是否使用知识库: {}", useRag);
            if (!useRag) {
                return chatClient.prompt()
                        .user(userMessage)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .stream()
                        .content();
            }
        }

        // 1. 从知识库检索相关知识
        List<Document> relevantDocs = knowledgeBaseService.search(userMessage, 10, null);

        // 2. 知识库内容放到系统提示词，页面只展示用户问题和助手回复
        String context = buildContext(relevantDocs);
        String systemPrompt = buildSystemPrompt(context, false);

        // 3. 系统提示词传知识库，用户消息只传原始问题
        return chatClient.prompt()
                .system(s -> s.text(systemPrompt))
                .user(userMessage)
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
                    String source = metadata.getOrDefault("source", "未知来源").toString();
                    return String.format("【来源: %s】\n%s", source, content);
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * 构建「由助手判断」时的系统提示词：仅包含文档名称列表，让模型判断是否需要根据知识库回答。
     * 模型只需回复 YES 或 NO，不写入对话记忆。
     */
    private String buildDecisionPrompt(String documentList) {
        return String.format("""
                你是一个判断助手。当前知识库中的文档列表如下（格式：空间名 - 文档名）：
                
                %s
                
                用户将提出一个问题。你需要仅根据上述文档名称（不含文档内容），判断是否需要根据这些知识库文档的内容来回答用户问题。
                - 若用户问题很可能与上述某类/某份文档相关，需要参考知识库内容才能更好回答，请只回复：YES
                - 若用户问题与上述文档明显无关，或仅凭常识即可回答，请只回复：NO
                
                不要输出任何解释，只输出 YES 或 NO。
                """, documentList);
    }

    /**
     * 构建系统提示词：仅包含知识库内容与作答要求，不包含用户问题。
     * 用户问题单独作为 user 消息传入，页面展示时不会带出知识库内容。
     * @param autoMode 保留参数，当前由助手判断逻辑已前置，此处仅用于「始终使用」模式
     */
    private String buildSystemPrompt(String context, boolean autoMode) {
        return String.format("""
                请基于以下知识库内容回答用户的问题。如果知识库中没有相关信息，请明确说明，不要编造答案。
                
                【知识库内容】
                %s
                
                请基于上述知识库内容，准确、清晰地回答用户的问题。
                """, context);
    }
}

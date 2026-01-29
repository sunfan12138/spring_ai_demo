package com.ifoodbuy.spring_ai_demo.service;

import com.ifoodbuy.spring_ai_demo.dto.ConversationDetailResponse;
import com.ifoodbuy.spring_ai_demo.dto.ConversationListResponse;
import com.ifoodbuy.spring_ai_demo.dto.ConversationSummaryResponse;
import com.ifoodbuy.spring_ai_demo.dto.MessageResponse;
import com.ifoodbuy.spring_ai_demo.entity.ConversationMetadata;
import com.ifoodbuy.spring_ai_demo.repository.ConversationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConversationHistoryService {

    private final ChatMemoryRepository chatMemoryRepository;
    private final ConversationMetadataRepository metadataRepository;

    public ConversationListResponse listConversations(String keyword, int page, int pageSize) {
        long total = metadataRepository.count(keyword);
        int offset = (page - 1) * pageSize;
        List<ConversationMetadata> metadataList = metadataRepository.findAll(keyword, offset, pageSize);
        
        List<ConversationSummaryResponse> summaries = new ArrayList<>();
        for (ConversationMetadata metadata : metadataList) {
            int count;
            LocalDateTime lastMessageTime = null;
            try {
                List<Message> messages = chatMemoryRepository.findByConversationId(metadata.getConversationId());
                count = messages.size();
                // 如果有消息，尝试从消息的metadata中获取时间，或者使用当前时间
                if (count > 0) {
                    // Spring AI的消息可能没有直接的时间戳，使用updatedAt或当前时间
                    lastMessageTime = metadata.getUpdatedAt();
                }
            } catch (Exception ignored) {
                count = 0;
            }
            
            // 使用最后消息时间，如果没有则使用metadata的updatedAt
            LocalDateTime finalUpdatedAt = lastMessageTime != null ? lastMessageTime : metadata.getUpdatedAt();
            
            summaries.add(new ConversationSummaryResponse(
                    metadata.getConversationId(),
                    metadata.getTitle(),
                    count,
                    metadata.getCreatedAt(),
                    finalUpdatedAt
            ));
        }
        
        int totalPages = (int) Math.ceil((double) total / pageSize);
        return new ConversationListResponse(summaries, total, page, pageSize, totalPages);
    }

    public ConversationDetailResponse getConversation(String conversationId) {
        if (metadataRepository.findById(conversationId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在或已删除");
        }
        List<Message> messages = chatMemoryRepository.findByConversationId(conversationId);
        List<MessageResponse> messageResponses = messages.stream()
                        .map(this::toDto)
                        .collect(Collectors.toList());
        return new ConversationDetailResponse(conversationId, messageResponses);
    }

    /** 逻辑删除对话：仅标记 metadata 为已删除，不删除聊天消息（可恢复） */
    @Transactional
    public void deleteConversation(String conversationId) {
        metadataRepository.deleteById(conversationId);
    }

    public void saveOrUpdateTitle(String conversationId, String title) {
        ConversationMetadata metadata = new ConversationMetadata();
        metadata.setConversationId(conversationId);
        metadata.setTitle(title);
        metadata.setCreatedAt(LocalDateTime.now());
        metadata.setUpdatedAt(LocalDateTime.now());
        metadataRepository.save(metadata);
    }

    public void updateTitle(String conversationId, String title) {
        metadataRepository.updateTitle(conversationId, title);
    }

    private MessageResponse toDto(Message message) {
        message.getMessageType();
        String role = message.getMessageType().getValue();
        String content = message.getText();
        return new MessageResponse(role, content);
    }
}

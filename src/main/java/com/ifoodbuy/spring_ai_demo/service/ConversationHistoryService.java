package com.ifoodbuy.spring_ai_demo.service;

import com.ifoodbuy.spring_ai_demo.dto.ConversationDetail;
import com.ifoodbuy.spring_ai_demo.dto.ConversationListResponse;
import com.ifoodbuy.spring_ai_demo.dto.ConversationSummary;
import com.ifoodbuy.spring_ai_demo.dto.MessageDto;
import com.ifoodbuy.spring_ai_demo.entity.ConversationMetadata;
import com.ifoodbuy.spring_ai_demo.repository.ConversationMetadataRepository;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ConversationHistoryService {

    private final ChatMemoryRepository chatMemoryRepository;
    private final ConversationMetadataRepository metadataRepository;

    public ConversationHistoryService(
            ChatMemoryRepository chatMemoryRepository,
            ConversationMetadataRepository metadataRepository) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.metadataRepository = metadataRepository;
    }

    public ConversationListResponse listConversations(String keyword, int page, int pageSize) {
        long total = metadataRepository.count(keyword);
        int offset = (page - 1) * pageSize;
        List<ConversationMetadata> metadataList = metadataRepository.findAll(keyword, offset, pageSize);
        
        List<ConversationSummary> summaries = new ArrayList<>();
        for (ConversationMetadata metadata : metadataList) {
            int count = 0;
            LocalDateTime lastMessageTime = null;
            try {
                List<Message> messages = chatMemoryRepository.findByConversationId(metadata.getConversationId());
                count = messages != null ? messages.size() : 0;
                // 如果有消息，尝试从消息的metadata中获取时间，或者使用当前时间
                if (count > 0 && messages != null) {
                    // Spring AI的消息可能没有直接的时间戳，使用updatedAt或当前时间
                    lastMessageTime = metadata.getUpdatedAt();
                }
            } catch (Exception ignored) {
                count = 0;
            }
            
            // 使用最后消息时间，如果没有则使用metadata的updatedAt
            LocalDateTime finalUpdatedAt = lastMessageTime != null ? lastMessageTime : metadata.getUpdatedAt();
            
            summaries.add(new ConversationSummary(
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

    public ConversationDetail getConversation(String conversationId) {
        List<Message> messages = chatMemoryRepository.findByConversationId(conversationId);
        List<MessageDto> messageDtos = messages != null ? messages.stream()
                .map(this::toDto)
                .collect(Collectors.toList()) : List.of();
        return new ConversationDetail(conversationId, messageDtos);
    }

    @Transactional
    public void deleteConversation(String conversationId) {
        chatMemoryRepository.deleteByConversationId(conversationId);
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

    private MessageDto toDto(Message message) {
        String role = message.getMessageType() != null ? message.getMessageType().getValue() : "unknown";
        String content = message.getText();
        return new MessageDto(role, content);
    }
}

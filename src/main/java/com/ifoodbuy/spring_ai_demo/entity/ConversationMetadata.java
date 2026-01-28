package com.ifoodbuy.spring_ai_demo.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationMetadata {
    private String conversationId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

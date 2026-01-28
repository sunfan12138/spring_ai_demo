package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSummary {

    private String conversationId;
    private String title;
    private int messageCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

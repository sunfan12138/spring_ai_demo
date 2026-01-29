package com.ifoodbuy.spring_ai_demo.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationMetadata {
    private String conversationId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑删除标记 */
    private Boolean deleted;
    /** 逻辑删除时间 */
    private LocalDateTime deletedAt;
}

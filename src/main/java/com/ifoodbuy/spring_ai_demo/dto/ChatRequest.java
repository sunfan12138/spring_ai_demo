package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    
    private String message;
    private String conversationId;
    private boolean useRag = false; // 是否使用知识库 RAG
}

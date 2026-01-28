package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationListResponse {
    private List<ConversationSummary> conversations;
    private long total;
    private int page;
    private int pageSize;
    private int totalPages;
}

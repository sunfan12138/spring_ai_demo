package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识库检索响应（包含查询重写信息）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchKnowledgeResponse {
    /** 原始查询 */
    private String originalQuery;
    /** 重写后的查询 */
    private String rewrittenQuery;
    /** 搜索结果列表 */
    private List<SearchResponse> results;
}

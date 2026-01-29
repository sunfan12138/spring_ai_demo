package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档块列表响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunksResponse {
    /** 文档块列表 */
    private List<SearchResponse> chunks;
}

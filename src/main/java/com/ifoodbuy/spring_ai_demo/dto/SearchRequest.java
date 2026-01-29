package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库搜索请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {
    /** 查询文本（必需） */
    private String query;
    /** 返回前 K 个结果，默认 5 */
    private Integer topK;
    /** 相似度阈值 (0.0-1.0)，默认 0.3 */
    private Double similarityThreshold;
}

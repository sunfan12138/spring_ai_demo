package com.ifoodbuy.spring_ai_demo.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识空间：全局唯一名称，用于分组管理文档
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSpace {
    private Long id;
    /** 空间名称，全局唯一 */
    private String name;
    private LocalDateTime createdAt;
}

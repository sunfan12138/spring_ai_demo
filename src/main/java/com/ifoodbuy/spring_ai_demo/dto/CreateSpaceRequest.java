package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建知识空间请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateSpaceRequest {
    /** 空间名称（全局唯一） */
    private String name;
}

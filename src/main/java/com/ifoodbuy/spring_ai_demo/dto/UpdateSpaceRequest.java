package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 更新知识空间请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSpaceRequest {
    /** 新的空间名称（全局唯一） */
    private String name;
}

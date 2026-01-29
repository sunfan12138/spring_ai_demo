package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分页请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageRequest {
    /** 页码，从 0 开始，默认 0 */
    private Integer page;
    /** 每页大小，默认 20 */
    private Integer size;
}

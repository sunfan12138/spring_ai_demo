package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按空间列出文档请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ListBySpaceRequest {
    /** 知识空间 ID */
    private Long spaceId;
    /** 页码，从 0 开始，默认 0 */
    private Integer page;
    /** 每页大小，默认 20 */
    private Integer size;
}

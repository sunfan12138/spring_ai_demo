package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 获取文档块请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetDocumentChunksRequest {
    /** 知识空间 ID */
    private Long spaceId;
    /** 文档名（文件名或文本录入标识） */
    private String filename;
}

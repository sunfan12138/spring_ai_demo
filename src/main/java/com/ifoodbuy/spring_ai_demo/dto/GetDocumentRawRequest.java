package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 获取文档原始文件请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetDocumentRawRequest {
    /** 文档 ID（路径参数） */
    private Long id;
    /** 内容处理方式：inline=浏览器内打开，attachment=下载（默认） */
    private String disposition;
}

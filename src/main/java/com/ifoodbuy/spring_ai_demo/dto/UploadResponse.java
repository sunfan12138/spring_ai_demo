package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上传文档/文本响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponse {
    /** 是否成功 */
    private Boolean success;
    /** 消息 */
    private String message;
    /** 文档块数量 */
    private Integer documentCount;
    /** 文件名（仅文档上传时） */
    private String filename;
}

package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 删除文档响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeleteResponse {
    /** 是否成功 */
    private Boolean success;
    /** 消息 */
    private String message;
}

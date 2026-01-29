package com.ifoodbuy.spring_ai_demo.dto;

import com.ifoodbuy.spring_ai_demo.entity.KnowledgeSpace;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识空间响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpaceResponse {
    /** 是否成功 */
    private Boolean success;
    /** 消息 */
    private String message;
    /** 知识空间数据 */
    private KnowledgeSpace data;
}

package com.ifoodbuy.spring_ai_demo.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库文档索引（通过 space_id 关联知识空间）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocument {
    private Long id;
    /** 知识空间 ID */
    private Long spaceId;
    /** 文档名称（文件名或文本录入标识），空间内唯一 */
    private String documentName;
    /** 文档块数量 */
    private Integer chunkCount;
    private LocalDateTime createdAt;
    /** 原始上传数据（文件二进制或文本 UTF-8 字节） */
    private byte[] rawData;
    /** 文件后缀名（如 pdf、txt、docx） */
    private String fileType;
    /** 文件大小（字节） */
    private Long fileSize;
}

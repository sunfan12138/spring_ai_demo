package com.ifoodbuy.spring_ai_demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 按空间分组的知识库列表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeListResponse {
    /** 空间列表，每个空间下有多条文档（非分页时使用） */
    private List<SpaceGroup> spaces;
    /** 分页时的扁平文档列表 */
    private List<DocumentItem> items;
    /** 总条数（分页时） */
    private Long total;
    /** 当前页（分页时，0-based） */
    private Integer page;
    /** 每页条数（分页时） */
    private Integer size;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SpaceGroup {
        private String spaceName;
        private List<DocumentItem> documents;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentItem {
        private Long id;
        /** 知识空间 ID */
        private Long spaceId;
        /** 空间名称（展示用，可选） */
        private String spaceName;
        private String documentName;
        private Integer chunkCount;
        private LocalDateTime createdAt;
        /** 文件后缀名（如 pdf、txt） */
        private String fileType;
        /** 文件大小（字节） */
        private Long fileSize;
    }

}

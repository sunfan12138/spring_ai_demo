package com.ifoodbuy.spring_ai_demo.controller;

import com.ifoodbuy.spring_ai_demo.dto.*;
import com.ifoodbuy.spring_ai_demo.entity.KnowledgeDocument;
import com.ifoodbuy.spring_ai_demo.entity.KnowledgeSpace;
import com.ifoodbuy.spring_ai_demo.repository.KnowledgeDocumentRepository;
import com.ifoodbuy.spring_ai_demo.repository.KnowledgeSpaceRepository;
import com.ifoodbuy.spring_ai_demo.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Optional;

/**
 * 知识库管理 API
 * 列表接口仅依赖 MySQL；上传/搜索/查看块依赖 Milvus（不可用时返回 503）
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeSpaceRepository knowledgeSpaceRepository;

    /**
     * 上传文档文件
     */
    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "spaceId", required = false) Long spaceId,
            @RequestParam(value = "documentName", required = false) String documentName) throws IOException {

        Map<String, Object> metadata = new HashMap<>();
        if (spaceId != null) metadata.put("spaceId", spaceId);
        if (documentName != null && !documentName.isBlank()) {
            metadata.put("documentName", documentName.trim());
        }

        int documentCount = knowledgeBaseService.uploadDocument(file, metadata);

        UploadResponse response = new UploadResponse(
                true,
                "文档上传成功",
                documentCount,
                file.getOriginalFilename()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 上传文本内容
     */
    @PostMapping("/upload-text")
    public ResponseEntity<UploadResponse> uploadText(@RequestBody UploadDocumentRequest request) {
        try {
            int documentCount = knowledgeBaseService.uploadText(
                    request.getText(),
                    request.getMetadata()
            );

            UploadResponse response = new UploadResponse(
                    true,
                    "文本上传成功",
                    documentCount,
                    null
            );

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            UploadResponse response = new UploadResponse(
                    false,
                    e.getMessage(),
                    null,
                    null
            );
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("文本上传失败", e);
            UploadResponse response = new UploadResponse(
                    false,
                    "文本上传失败: " + e.getMessage(),
                    null,
                    null
            );
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 删除文档
     */
    @PostMapping("/delete")
    public ResponseEntity<DeleteResponse> deleteDocument(@RequestBody Map<String, Object> metadata) {
        try {
            knowledgeBaseService.deleteByMetadata(metadata);
            DeleteResponse response = new DeleteResponse(
                    true,
                    "删除请求已提交"
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("删除失败", e);
            DeleteResponse response = new DeleteResponse(
                    false,
                    "删除失败: " + e.getMessage()
            );
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 搜索知识库
     */
    @GetMapping("/search") // 建议明确指定请求方式
    public ResponseEntity<SearchKnowledgeResponse> search(SearchRequest request) {
        // 限制 topK 范围，防止因前端恶意传参导致内存溢出
        int topK = (request.getTopK() != null) ? Math.min(Math.max(request.getTopK(), 1), 50) : 5;

        // 默认相似度阈值由 Service 层控制，这里仅做透传
        Double similarityThreshold = request.getSimilarityThreshold();

        // 2. 调用优化后的 Service 方法（含重写和前缀）
        var searchResult = knowledgeBaseService.searchWithRewrite(request.getQuery(), topK, similarityThreshold);
        List<Document> documents = searchResult.documents();

        // 3. 流式转换并精简元数据
        List<SearchResponse> responses = documents.stream()
                .map(doc -> {
                    Double similarity = calculateSimilarity(doc.getMetadata().get("distance"));

                    // 可以在这里移除一些不需要给前端展示的敏感元数据，如 space_id
                    Map<String, Object> cleanMetadata = new HashMap<>(doc.getMetadata());
                    cleanMetadata.remove("distance"); // 已经单独提取，从 Map 中移除

                    return new SearchResponse(
                            doc.getFormattedContent(),
                            cleanMetadata,
                            similarity
                    );
                })
                .collect(Collectors.toList());

        SearchKnowledgeResponse response = new SearchKnowledgeResponse(
                searchResult.originalQuery(),
                searchResult.rewrittenQuery(),
                responses
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 提取并归一化相似度评分
     */
    private Double calculateSimilarity(Object distanceObj) {
        if (distanceObj instanceof Number) {
            double dist = ((Number) distanceObj).doubleValue();

            // 对于 Milvus 的 COSINE 度量：
            // 1. 值范围通常在 [0, 2] 之间。
            // 2. 0 代表完全一致，2 代表完全相反。
            // 3. 我们需要的相似度 = 1 - (dist / 1.0) 或者是直接 1 - dist (取决于 Milvus 返回是否已平方)
            // 在 BGE 模型下，通常使用 1.0 - dist 并截断
            double similarity = 1.0 - dist;

            // 确保结果在 0.0 - 1.0 之间，并保留 4 位小数提升展示美观度
            return BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, similarity)))
                    .setScale(4, RoundingMode.HALF_UP)
                    .doubleValue();
        }
        return null;
    }

    /**
     * 分页列出已上传知识库（扁平列表，用于左侧边栏）
     */
    @GetMapping("/list/paged")
    public ResponseEntity<KnowledgeListResponse> listPaged(@ModelAttribute PageRequest request) {
        int page = request.getPage();
        int size = request.getSize();
        if (page < 0) page = 0;
        if (size < 1 || size > 100) size = 20;
        long total = knowledgeDocumentRepository.count();
        int offset = page * size;
        List<KnowledgeDocument> list = knowledgeDocumentRepository.findAll(offset, size);
        List<KnowledgeListResponse.DocumentItem> items = list.stream()
                .map(doc -> {
                    Long sid = doc.getSpaceId();
                    String spaceName = knowledgeSpaceRepository.findById(sid != null ? sid : 0L).map(KnowledgeSpace::getName).orElse("");
                    return new KnowledgeListResponse.DocumentItem(
                            doc.getId(),
                            sid,
                            spaceName,
                            doc.getDocumentName(),
                            doc.getChunkCount(),
                            doc.getCreatedAt(),
                            doc.getFileType(),
                            doc.getFileSize()
                    );
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(new KnowledgeListResponse(null, items, total, page, size));
    }

    /**
     * 获取所有知识空间（第一级列表）
     */
    @GetMapping("/spaces")
    public ResponseEntity<List<KnowledgeSpace>> listSpaces() {
        return ResponseEntity.ok(knowledgeSpaceRepository.findAll());
    }

    /**
     * 新建知识空间（空间名全局唯一）
     */
    @PostMapping("/spaces")
    public ResponseEntity<SpaceResponse> createSpace(@RequestBody CreateSpaceRequest request) {
        String name = request.getName();
        if (name == null || name.isBlank()) {
            SpaceResponse response = new SpaceResponse(
                    false,
                    "空间名称不能为空",
                    null
            );
            return ResponseEntity.badRequest().body(response);
        }
        name = name.trim();
        if (knowledgeSpaceRepository.existsByName(name)) {
            SpaceResponse response = new SpaceResponse(
                    false,
                    "空间名称已存在，请使用其他名称",
                    null
            );
            return ResponseEntity.badRequest().body(response);
        }
        KnowledgeSpace space = knowledgeSpaceRepository.insert(name);
        SpaceResponse response = new SpaceResponse(
                true,
                "空间创建成功",
                space
        );
        return ResponseEntity.ok(response);
    }

    /**
     * 修改知识空间名称（全局唯一）
     */
    @PutMapping("/spaces/{id}")
    public ResponseEntity<SpaceResponse> updateSpace(@PathVariable long id, @RequestBody UpdateSpaceRequest request) {
        String newName = request.getName();
        if (newName == null || newName.isBlank()) {
            SpaceResponse response = new SpaceResponse(
                    false,
                    "空间名称不能为空",
                    null
            );
            return ResponseEntity.badRequest().body(response);
        }
        newName = newName.trim();
        Optional<KnowledgeSpace> existing = knowledgeSpaceRepository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        KnowledgeSpace space = existing.get();
        if (newName.equals(space.getName())) {
            SpaceResponse response = new SpaceResponse(
                    true,
                    "空间名称未变化",
                    space
            );
            return ResponseEntity.ok(response);
        }
        if (knowledgeSpaceRepository.existsByName(newName)) {
            SpaceResponse response = new SpaceResponse(
                    false,
                    "空间名称已存在，请使用其他名称",
                    null
            );
            return ResponseEntity.badRequest().body(response);
        }
        knowledgeSpaceRepository.updateName(id, newName);
        KnowledgeSpace updatedSpace = knowledgeSpaceRepository.findById(id).orElse(space);
        SpaceResponse response = new SpaceResponse(
                true,
                "空间名称更新成功",
                updatedSpace
        );
        return ResponseEntity.ok(response);
    }

    /**
     * 按空间 ID 分页列出文档（第二级列表）
     */
    @GetMapping("/list/by-space")
    public ResponseEntity<KnowledgeListResponse> listBySpace(@ModelAttribute ListBySpaceRequest request) {
        Long spaceId = request.getSpaceId();
        int page = request.getPage() != null ? request.getPage() : 0;
        int size = request.getSize() != null ? request.getSize() : 20;
        if (page < 0) page = 0;
        if (size < 1 || size > 100) size = 20;
        if (spaceId == null) {
            return ResponseEntity.badRequest().build();
        }
        long total = knowledgeDocumentRepository.countBySpaceId(spaceId);
        int offset = page * size;
        List<KnowledgeDocument> list = knowledgeDocumentRepository.findBySpaceId(spaceId, offset, size);
        String spaceName = knowledgeSpaceRepository.findById(spaceId).map(KnowledgeSpace::getName).orElse("");
        List<KnowledgeListResponse.DocumentItem> items = list.stream()
                .map(doc -> new KnowledgeListResponse.DocumentItem(
                        doc.getId(),
                        doc.getSpaceId(),
                        spaceName,
                        doc.getDocumentName(),
                        doc.getChunkCount(),
                        doc.getCreatedAt(),
                        doc.getFileType(),
                        doc.getFileSize()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(new KnowledgeListResponse(null, items, total, page, size));
    }

    /**
     * 获取文档原始文件（二进制）
     *
     * @param disposition inline=浏览器内打开，attachment=下载（默认）
     */
    @GetMapping("/documents/{id}/raw")
    public ResponseEntity<?> getDocumentRaw(
            @PathVariable Long id,
            @RequestParam(value = "disposition", defaultValue = "attachment") String disposition) {
        if (id == null) return ResponseEntity.badRequest().build();
        Optional<KnowledgeDocument> doc = knowledgeDocumentRepository.findById(id);
        if (doc.isEmpty()) return ResponseEntity.notFound().build();
        KnowledgeDocument d = doc.get();
        byte[] raw = d.getRawData();
        if (raw == null || raw.length == 0) {
            return ResponseEntity.noContent().build();
        }
        String ext = (d.getFileType() != null && !d.getFileType().isBlank()) ? d.getFileType().trim().toLowerCase() : "";
        MediaType mediaType = extensionToMediaType(ext);
        String filename = d.getDocumentName() != null ? d.getDocumentName() : ("document" + (ext.isEmpty() ? "" : "." + ext));
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        boolean inline = "inline".equalsIgnoreCase(disposition);
        String contentDisposition = (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .contentLength(raw.length)
                .body(raw);
    }

    private static MediaType extensionToMediaType(String ext) {
        if (ext == null || ext.isEmpty()) return MediaType.APPLICATION_OCTET_STREAM;
        return switch (ext) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "txt" -> MediaType.TEXT_PLAIN;
            case "html", "htm" -> MediaType.TEXT_HTML;
            case "json" -> MediaType.APPLICATION_JSON;
            case "xml" -> MediaType.APPLICATION_XML;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "doc" -> MediaType.parseMediaType("application/msword");
            case "docx" ->
                    MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "xls" -> MediaType.parseMediaType("application/vnd.ms-excel");
            case "xlsx" ->
                    MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    /**
     * 查看某文档在指定空间下的所有块（预览）
     */
    @GetMapping("/documents/chunks")
    public ResponseEntity<DocumentChunksResponse> getDocumentChunks(@ModelAttribute GetDocumentChunksRequest request) {
        try {
            Long spaceId = request.getSpaceId();
            String filename = request.getFilename();
            List<Document> chunks = knowledgeBaseService.searchChunksBySpaceIdAndFilename(spaceId, filename);
            List<SearchResponse> responses = chunks.stream()
                    .map(doc -> new SearchResponse(
                            doc.getFormattedContent(),
                            doc.getMetadata(),
                            null
                    ))
                    .collect(Collectors.toList());
            DocumentChunksResponse response = new DocumentChunksResponse(responses);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取文档块失败", e);
            return ResponseEntity.status(500).build();
        }
    }
}

package com.ifoodbuy.spring_ai_demo.controller;

import com.ifoodbuy.spring_ai_demo.dto.KnowledgeListResponse;
import com.ifoodbuy.spring_ai_demo.dto.SearchResponse;
import com.ifoodbuy.spring_ai_demo.dto.UploadDocumentRequest;
import com.ifoodbuy.spring_ai_demo.entity.KnowledgeDocument;
import com.ifoodbuy.spring_ai_demo.entity.KnowledgeSpace;
import com.ifoodbuy.spring_ai_demo.repository.KnowledgeDocumentRepository;
import com.ifoodbuy.spring_ai_demo.repository.KnowledgeSpaceRepository;
import com.ifoodbuy.spring_ai_demo.service.KnowledgeBaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeSpaceRepository knowledgeSpaceRepository;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService,
                                  KnowledgeDocumentRepository knowledgeDocumentRepository,
                                  KnowledgeSpaceRepository knowledgeSpaceRepository) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeSpaceRepository = knowledgeSpaceRepository;
    }

    private ResponseEntity<Map<String, Object>> serviceUnavailable() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", "知识库服务不可用，请确保 Milvus 已启动且 application.yaml 中 spring.ai.vectorstore.milvus 配置正确");
        return ResponseEntity.status(503).body(body);
    }

    /**
     * 上传文档文件
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "spaceId", required = false) Long spaceId,
            @RequestParam(value = "documentName", required = false) String documentName) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            if (spaceId != null) metadata.put("spaceId", spaceId);
            if (documentName != null && !documentName.isBlank()) {
                metadata.put("documentName", documentName.trim());
            }

            int documentCount = knowledgeBaseService.uploadDocument(file, metadata);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "文档上传成功");
            response.put("documentCount", documentCount);
            response.put("filename", file.getOriginalFilename());

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (IOException e) {
            log.error("文档上传失败", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "文档上传失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 上传文本内容
     */
    @PostMapping("/upload-text")
    public ResponseEntity<Map<String, Object>> uploadText(@RequestBody UploadDocumentRequest request) {
        try {
            int documentCount = knowledgeBaseService.uploadText(
                    request.getText(),
                    request.getMetadata()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "文本上传成功");
            response.put("documentCount", documentCount);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("文本上传失败", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "文本上传失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 删除文档
     */
    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> deleteDocument(@RequestBody Map<String, Object> metadata) {
        try {
            knowledgeBaseService.deleteByMetadata(metadata);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "删除请求已提交");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("删除失败", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 搜索知识库
     */
    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam("query") String query,
            @RequestParam(value = "topK", defaultValue = "5") int topK,
            @RequestParam(value = "similarityThreshold", required = false) Double similarityThreshold) {
        try {
            List<Document> documents = knowledgeBaseService.search(query, topK, similarityThreshold);

            List<SearchResponse> responses = documents.stream()
                    .map(doc -> {
                        // 从文档中提取相似度（如果有）
                        Double similarity = null;
                        if (doc.getMetadata().containsKey("distance")) {
                            Object distance = doc.getMetadata().get("distance");
                            if (distance instanceof Number) {
                                // 将距离转换为相似度（假设是余弦距离，相似度 = 1 - 距离）
                                similarity = 1.0 - ((Number) distance).doubleValue();
                            }
                        }

                        return new SearchResponse(
                                doc.getFormattedContent(),
                                doc.getMetadata(),
                                similarity
                        );
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(responses);
        } catch (Exception e) {
            log.error("搜索失败", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 按空间分组列出已上传知识库（仅依赖 MySQL）
     */
    @GetMapping("/list")
    public ResponseEntity<KnowledgeListResponse> listBySpace() {
        List<KnowledgeDocument> all = knowledgeDocumentRepository.findAll();
        Map<Long, List<KnowledgeListResponse.DocumentItem>> bySpaceId = new LinkedHashMap<>();
        for (KnowledgeDocument doc : all) {
            Long sid = doc.getSpaceId();
            String spaceName = knowledgeSpaceRepository.findById(sid != null ? sid : 0L).map(KnowledgeSpace::getName).orElse("");
            bySpaceId.computeIfAbsent(sid != null ? sid : 0L, k -> new ArrayList<>()).add(
                    new KnowledgeListResponse.DocumentItem(
                            doc.getId(),
                            sid,
                            spaceName,
                            doc.getDocumentName(),
                            doc.getChunkCount(),
                            doc.getCreatedAt(),
                            doc.getFileType(),
                            doc.getFileSize()
                    )
            );
        }
        List<KnowledgeListResponse.SpaceGroup> spaces = bySpaceId.entrySet().stream()
                .map(e -> new KnowledgeListResponse.SpaceGroup(
                        knowledgeSpaceRepository.findById(e.getKey()).map(KnowledgeSpace::getName).orElse(""),
                        e.getValue()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(new KnowledgeListResponse(spaces, null, null, null, null));
    }

    /**
     * 分页列出已上传知识库（扁平列表，用于左侧边栏）
     */
    @GetMapping("/list/paged")
    public ResponseEntity<KnowledgeListResponse> listPaged(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
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
    public ResponseEntity<?> createSpace(@RequestBody Map<String, String> body) {
        String name = body != null ? body.get("name") : null;
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "空间名称不能为空"));
        }
        name = name.trim();
        if (knowledgeSpaceRepository.existsByName(name)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "空间名称已存在，请使用其他名称"));
        }
        KnowledgeSpace space = knowledgeSpaceRepository.insert(name);
        return ResponseEntity.ok(space);
    }

    /**
     * 修改知识空间名称（全局唯一）
     */
    @PutMapping("/spaces/{id}")
    public ResponseEntity<?> updateSpace(@PathVariable long id, @RequestBody Map<String, String> body) {
        String newName = body != null ? body.get("name") : null;
        if (newName == null || newName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "空间名称不能为空"));
        }
        newName = newName.trim();
        Optional<KnowledgeSpace> existing = knowledgeSpaceRepository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        KnowledgeSpace space = existing.get();
        if (newName.equals(space.getName())) {
            return ResponseEntity.ok(space);
        }
        if (knowledgeSpaceRepository.existsByName(newName)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "空间名称已存在，请使用其他名称"));
        }
        knowledgeSpaceRepository.updateName(id, newName);
        return ResponseEntity.ok(knowledgeSpaceRepository.findById(id).orElse(space));
    }

    /**
     * 按空间 ID 分页列出文档（第二级列表）
     */
    @GetMapping("/list/by-space")
    public ResponseEntity<KnowledgeListResponse> listBySpace(
            @RequestParam("spaceId") Long spaceId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
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
        switch (ext) {
            case "pdf": return MediaType.APPLICATION_PDF;
            case "txt": return MediaType.TEXT_PLAIN;
            case "html": case "htm": return MediaType.TEXT_HTML;
            case "json": return MediaType.APPLICATION_JSON;
            case "xml": return MediaType.APPLICATION_XML;
            case "jpg": case "jpeg": return MediaType.IMAGE_JPEG;
            case "png": return MediaType.IMAGE_PNG;
            case "gif": return MediaType.IMAGE_GIF;
            case "webp": return MediaType.parseMediaType("image/webp");
            case "doc": return MediaType.parseMediaType("application/msword");
            case "docx": return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "xls": return MediaType.parseMediaType("application/vnd.ms-excel");
            case "xlsx": return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            default: return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /**
     * 查看某文档在指定空间下的所有块（预览）
     */
    @GetMapping("/documents/chunks")
    public ResponseEntity<?> getDocumentChunks(
            @RequestParam("spaceId") Long spaceId,
            @RequestParam("filename") String filename) {
        try {
            List<Document> chunks = knowledgeBaseService.searchChunksBySpaceIdAndFilename(spaceId, filename);
            List<SearchResponse> responses = chunks.stream()
                    .map(doc -> new SearchResponse(
                            doc.getFormattedContent(),
                            doc.getMetadata(),
                            null
                    ))
                    .collect(Collectors.toList());
            return ResponseEntity.ok(responses);
        } catch (Exception e) {
            log.error("获取文档块失败", e);
            return ResponseEntity.status(500).build();
        }
    }
}

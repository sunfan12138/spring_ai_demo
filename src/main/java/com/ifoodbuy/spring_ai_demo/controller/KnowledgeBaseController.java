package com.ifoodbuy.spring_ai_demo.controller;

import com.ifoodbuy.spring_ai_demo.dto.SearchResponse;
import com.ifoodbuy.spring_ai_demo.dto.UploadDocumentRequest;
import com.ifoodbuy.spring_ai_demo.service.KnowledgeBaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库管理 API
 * 只有在 VectorStore Bean 存在时才会创建（即 Milvus 已配置）
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@ConditionalOnBean(VectorStore.class)
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 上传文档文件
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", required = false) String category) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            if (category != null) {
                metadata.put("category", category);
            }

            int documentCount = knowledgeBaseService.uploadDocument(file, metadata);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "文档上传成功");
            response.put("documentCount", documentCount);
            response.put("filename", file.getOriginalFilename());

            return ResponseEntity.ok(response);
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
    public ResponseEntity<List<SearchResponse>> search(
            @RequestParam("query") String query,
            @RequestParam(value = "topK", defaultValue = "5") int topK) {
        try {
            List<Document> documents = knowledgeBaseService.search(query, topK);

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
}

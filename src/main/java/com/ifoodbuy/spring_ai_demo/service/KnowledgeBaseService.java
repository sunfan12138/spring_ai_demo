package com.ifoodbuy.spring_ai_demo.service;

import com.ifoodbuy.spring_ai_demo.repository.KnowledgeDocumentRepository;
import com.ifoodbuy.spring_ai_demo.repository.KnowledgeSpaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.milvus.MilvusSearchRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库服务
 * 负责文档上传、向量化存储和检索
 * 使用 Spring Boot 自动配置的 VectorStore Bean
 */
@Slf4j
@Service
public class KnowledgeBaseService {

    private final VectorStore vectorStore;
    private final TextSplitter textSplitter;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeSpaceRepository knowledgeSpaceRepository;

    public KnowledgeBaseService(VectorStore vectorStore, KnowledgeDocumentRepository knowledgeDocumentRepository,
                               KnowledgeSpaceRepository knowledgeSpaceRepository) {
        this.vectorStore = vectorStore;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeSpaceRepository = knowledgeSpaceRepository;
        // 使用 TokenTextSplitter builder 创建
        // 对于 ONNX all-MiniLM-L6-v2 模型，最大 token 数为 512，因此设置 chunkSize 为 400 比较安全
        this.textSplitter = TokenTextSplitter.builder()
                .withChunkSize(400)
                .withMinChunkSizeChars(100)  // 最小 chunk 字符数
                .withMinChunkLengthToEmbed(50)  // 最小嵌入长度
                .withKeepSeparator(false)  // 是否保留分隔符
                .build();
    }

    /**
     * 上传文档到知识库
     *
     * @param file 文档文件
     * @param metadata 文档元数据（可选）
     * @return 上传的文档数量
     */
    public int uploadDocument(MultipartFile file, Map<String, Object> metadata) throws IOException {
        log.info("开始上传文档: {}, 大小: {} bytes", file.getOriginalFilename(), file.getSize());

        Long spaceId = parseSpaceId(metadata);
        if (spaceId == null || knowledgeSpaceRepository.findById(spaceId).isEmpty()) {
            throw new IllegalArgumentException("知识空间不存在，请先创建或选择已有空间");
        }
        // 文档名称：优先使用 metadata 中的 documentName，否则使用文件名（不含后缀）
        String customName = metadata != null && metadata.get("documentName") != null 
                ? metadata.get("documentName").toString().trim() : null;
        String originalFilename = file.getOriginalFilename();
        String documentName;
        if (customName != null && !customName.isBlank()) {
            documentName = customName;
        } else if (originalFilename != null && !originalFilename.isBlank()) {
            // 去掉后缀名
            int lastDot = originalFilename.lastIndexOf('.');
            documentName = lastDot > 0 ? originalFilename.substring(0, lastDot) : originalFilename;
        } else {
            throw new IllegalArgumentException("文档名称不能为空");
        }
        if (knowledgeDocumentRepository.existsBySpaceIdAndDocumentName(spaceId, documentName)) {
            throw new IllegalArgumentException("该空间下已存在同名文档，请使用其他名称或删除后重试");
        }

        // 使用 Tika 读取文档内容
        TikaDocumentReader reader = new TikaDocumentReader(file.getResource());
        List<Document> documents = reader.get();

        // 添加元数据（向量库用 space_id + filename 过滤）
        Map<String, Object> docMetadata = new HashMap<>();
        if (metadata != null) {
            docMetadata.putAll(metadata);
        }
        docMetadata.put("space_id", spaceId);
        docMetadata.put("filename", documentName);
        docMetadata.put("contentType", file.getContentType());
        docMetadata.put("size", file.getSize());

        // 先分割文档
        List<Document> processedDocuments = textSplitter.apply(documents);

        // 为分割后的每个文档块添加元数据
        processedDocuments.forEach(doc -> doc.getMetadata().putAll(docMetadata));

        // 存储到向量数据库
        vectorStore.add(processedDocuments);

        // 原始文件二进制、文件后缀名、大小
        byte[] rawData = file.getBytes();
        String fileType = getFileExtension(documentName);
        long fileSize = file.getSize() >= 0 ? file.getSize() : rawData.length;

        // 记录到文档索引表（含原始数据）
        knowledgeDocumentRepository.insert(spaceId, documentName, processedDocuments.size(), rawData, fileType, fileSize);

        log.info("文档上传完成，共 {} 个文档块", processedDocuments.size());
        return processedDocuments.size();
    }

    private static String getFileExtension(String filename) {
        if (filename == null || filename.isBlank()) return "";
        int i = filename.lastIndexOf('.');
        if (i < 0 || i >= filename.length() - 1) return "";
        return filename.substring(i + 1).trim().toLowerCase();
    }

    private static Long parseSpaceId(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object v = metadata.get("spaceId");
        if (v == null) v = metadata.get("category");
        if (v instanceof Number) return ((Number) v).longValue();
        if (v != null) {
            try {
                return Long.parseLong(v.toString().trim());
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /**
     * 上传文本内容到知识库
     *
     * @param text 文本内容
     * @param metadata 文档元数据（可选）
     * @return 上传的文档数量
     */
    public int uploadText(String text, Map<String, Object> metadata) {
        log.info("开始上传文本，长度: {}", text.length());

        Long spaceId = parseSpaceId(metadata);
        if (spaceId == null || knowledgeSpaceRepository.findById(spaceId).isEmpty()) {
            throw new IllegalArgumentException("知识空间不存在，请先创建或选择已有空间");
        }
        String docName = metadata != null && metadata.get("source") != null ? metadata.get("source").toString().trim() : null;
        if (docName == null || docName.isBlank()) {
            docName = "文本-" + System.currentTimeMillis();
        }
        if (knowledgeDocumentRepository.existsBySpaceIdAndDocumentName(spaceId, docName)) {
            throw new IllegalArgumentException("该空间下已存在同名文档，请使用其他名称或删除后重试");
        }

        Map<String, Object> docMetadata = new HashMap<>();
        if (metadata != null) {
            docMetadata.putAll(metadata);
        }
        docMetadata.put("space_id", spaceId);
        docMetadata.put("filename", docName);

        Document document = new Document(text, docMetadata);
        List<Document> documents = textSplitter.apply(List.of(document));

        vectorStore.add(documents);

        // 原始数据：文本 UTF-8 字节，后缀名 txt
        byte[] rawData = text.getBytes(StandardCharsets.UTF_8);
        String fileType = "txt";
        long fileSize = rawData.length;

        // 记录到文档索引表（含原始数据）
        knowledgeDocumentRepository.insert(spaceId, docName, documents.size(), rawData, fileType, fileSize);

        log.info("文本上传完成，共 {} 个文档块", documents.size());
        return documents.size();
    }

    /**
     * 搜索相关知识
     *
     * @param query 查询文本
     * @param topK 返回前 K 个结果
     * @param similarityThreshold 相似度阈值（0.0-1.0），默认 0.7
     * @return 相关文档列表
     */
    public List<Document> search(String query, int topK, Double similarityThreshold) {
        double threshold = similarityThreshold != null && similarityThreshold >= 0.0 && similarityThreshold <= 1.0 
                ? similarityThreshold : 0.7;
        log.info("搜索知识库，查询: {}, topK: {}, 相似度阈值: {}", query, topK, threshold);

        // 使用 MilvusSearchRequest 以支持 Milvus 特定参数
        // 对于 IVF_FLAT 索引，设置 nprobe 参数以提高搜索准确性
        MilvusSearchRequest searchRequest = MilvusSearchRequest.milvusBuilder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .searchParamsJson("{\"nprobe\":128}") // 对于 IVF_FLAT 索引很重要
                .build();

        List<Document> results = vectorStore.similaritySearch(searchRequest);

        log.info("搜索完成，找到 {} 个相关文档", results.size());
        return results;
    }

    /**
     * 按空间 ID 和文档名查询该文档的所有块（用于「查看」预览）。
     * 向量库 metadata 使用 space_id + filename 过滤。
     *
     * @param spaceId 知识空间 ID
     * @param filename 文档名（文件名或文本录入标识）
     * @return 文档块列表
     */
    public List<Document> searchChunksBySpaceIdAndFilename(Long spaceId, String filename) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression filterExpression = b.and(
                b.eq("space_id", spaceId != null ? spaceId : 0L),
                b.eq("filename", filename != null ? filename : "")
        ).build();

        MilvusSearchRequest request = MilvusSearchRequest.milvusBuilder()
                .query(" ")
                .topK(500)
                .similarityThreshold(0.0)
                .filterExpression(filterExpression)
                .searchParamsJson("{\"nprobe\":128}")
                .build();
        return vectorStore.similaritySearch(request);
    }

    /**
     * 删除文档（根据元数据过滤）
     *
     * @param metadata 元数据过滤条件
     */
    public void deleteByMetadata(Map<String, Object> metadata) {
        log.info("删除文档，过滤条件: {}", metadata);
        // Milvus 向量存储可能不支持直接删除，这里需要根据实际 API 实现
        // 暂时记录日志
        log.warn("删除功能需要根据 Milvus API 实现");
    }
}

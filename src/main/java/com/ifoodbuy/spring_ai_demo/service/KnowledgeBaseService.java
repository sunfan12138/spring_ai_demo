package com.ifoodbuy.spring_ai_demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusSearchRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
@ConditionalOnBean(VectorStore.class)
public class KnowledgeBaseService {

    private final VectorStore vectorStore;
    private final TextSplitter textSplitter;

    public KnowledgeBaseService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
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

        // 使用 Tika 读取文档内容
        TikaDocumentReader reader = new TikaDocumentReader(file.getResource());
        List<Document> documents = reader.get();

        // 添加元数据
        Map<String, Object> docMetadata = new HashMap<>();
        if (metadata != null) {
            docMetadata.putAll(metadata);
        }
        docMetadata.put("filename", file.getOriginalFilename());
        docMetadata.put("contentType", file.getContentType());
        docMetadata.put("size", file.getSize());

        // 先分割文档
        List<Document> processedDocuments = textSplitter.apply(documents);

        // 为分割后的每个文档块添加元数据
        processedDocuments.forEach(doc -> doc.getMetadata().putAll(docMetadata));

        // 存储到向量数据库
        vectorStore.add(processedDocuments);

        log.info("文档上传完成，共 {} 个文档块", processedDocuments.size());
        return processedDocuments.size();
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

        Map<String, Object> docMetadata = new HashMap<>();
        if (metadata != null) {
            docMetadata.putAll(metadata);
        }

        Document document = new Document(text, docMetadata);
        List<Document> documents = textSplitter.apply(List.of(document));

        vectorStore.add(documents);

        log.info("文本上传完成，共 {} 个文档块", documents.size());
        return documents.size();
    }

    /**
     * 搜索相关知识
     *
     * @param query 查询文本
     * @param topK 返回前 K 个结果
     * @return 相关文档列表
     */
    public List<Document> search(String query, int topK) {
        log.info("搜索知识库，查询: {}, topK: {}", query, topK);

        // 使用 MilvusSearchRequest 以支持 Milvus 特定参数
        // 对于 IVF_FLAT 索引，设置 nprobe 参数以提高搜索准确性
        MilvusSearchRequest searchRequest = MilvusSearchRequest.milvusBuilder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.7) // 相似度阈值
                .searchParamsJson("{\"nprobe\":128}") // 对于 IVF_FLAT 索引很重要
                .build();

        List<Document> results = vectorStore.similaritySearch(searchRequest);

        log.info("搜索完成，找到 {} 个相关文档", results.size());
        return results;
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

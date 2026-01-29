package com.ifoodbuy.spring_ai_demo.service;

import com.ifoodbuy.spring_ai_demo.dto.UploadDocumentRequest;
import com.ifoodbuy.spring_ai_demo.repository.KnowledgeDocumentRepository;
import com.ifoodbuy.spring_ai_demo.repository.KnowledgeSpaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.milvus.MilvusSearchRequest;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库服务
 * 负责文档上传、向量化存储和检索
 * 使用 Spring Boot 自动配置的 VectorStore Bean
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final VectorStore vectorStore;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeSpaceRepository knowledgeSpaceRepository;
    private final ChatModel chatModel;

    // 使用 TokenTextSplitter builder 创建
    // 对于 ONNX all-MiniLM-L6-v2 模型，最大 token 数为 512，因此设置 chunkSize 为 400 比较安全
    private static final TextSplitter textSplitter = TokenTextSplitter.builder()
            .withChunkSize(400)
            .withMinChunkSizeChars(100)  // 最小 chunk 字符数
            .withMinChunkLengthToEmbed(50)  // 最小嵌入长度
            .withKeepSeparator(false)  // 是否保留分隔符
            .build();

    /**
     * 上传文档到知识库
     *
     * @param request
     * @return 上传的文档数量
     */
    public int uploadDocument(UploadDocumentRequest request) throws IOException {
        MultipartFile file = request.getFile();
        log.info("开始上传文档: {}, 大小: {} bytes", file.getOriginalFilename(), file.getSize());

        if (knowledgeSpaceRepository.findById(request.getSpaceId()).isEmpty()) {
            throw new IllegalArgumentException("知识空间不存在，请先创建或选择已有空间");
        }
        if (knowledgeDocumentRepository.existsBySpaceIdAndDocumentName(request.getSpaceId(), request.getDocumentName())) {
            throw new IllegalArgumentException("该空间下已存在同名文档，请使用其他名称或删除后重试");
        }

        // 使用 Tika 读取文档内容
        Resource resource = file.getResource();
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();

        // 添加元数据（向量库用 space_id + filename 过滤）
        Map<String, Object> docMetadata = new HashMap<>();
        docMetadata.put("space_id", request.getSpaceId());
        docMetadata.put("document_name", request.getDocumentName());

        // 先分割文档
        List<Document> processedDocuments = textSplitter.apply(documents);
        // 可选：记录总分块数，方便校验完整性
        docMetadata.put("total_chunks", processedDocuments.size());

        // 关键优化：为每个块注入唯一的顺序索引 (chunk_index)
        for (int i = 0; i < processedDocuments.size(); i++) {
            Document doc = processedDocuments.get(i);
            // 复制一份基础元数据，避免所有 doc 共享同一个 Map 导致并发风险（如果有的话）
            Map<String, Object> currentMetadata = new HashMap<>(docMetadata);
            // 注入块索引：从 0 开始
            currentMetadata.put("chunk_index", i);
            doc.getMetadata().putAll(currentMetadata);
        }

        // 存储到向量数据库
        vectorStore.add(processedDocuments);

        // 原始文件二进制、文件后缀名、大小
        byte[] rawData = file.getBytes();
        String fileType = getFileExtension(file.getOriginalFilename());
        long fileSize = file.getSize() >= 0 ? file.getSize() : rawData.length;

        // 记录到文档索引表（含原始数据）
        knowledgeDocumentRepository.insert(request.getSpaceId(), request.getDocumentName(), processedDocuments.size(), rawData, fileType, fileSize);

        log.info("文档上传完成，共 {} 个文档块", processedDocuments.size());
        return processedDocuments.size();
    }

    private static String getFileExtension(String filename) {
        if (filename == null || filename.isBlank()) return "";
        int i = filename.lastIndexOf('.');
        if (i < 0 || i >= filename.length() - 1) return "";
        return filename.substring(i + 1).trim().toLowerCase();
    }

    /**
     * 使用 LLM 重写查询，优化搜索效果
     *
     * @param originalQuery 原始查询
     * @return 重写后的查询
     */
    private String rewriteQuery(String originalQuery) {
        try {
            String promptTemplate = """
                    你是一个查询优化专家。请将用户输入的搜索查询改写为更适合向量检索的查询文本。
                    
                    改写原则：
                    1. 保留原查询的核心意图和关键词
                    2. 扩展同义词和相关概念（如果有助于检索）
                    3. 优化表达方式，使其更符合知识库文档的表述习惯
                    4. 如果查询是部分文本片段，尝试补充完整语义
                    5. 保持简洁，不要添加无关信息
                    6. 如果原查询已经很清晰，可以不做大幅改动
                    
                    原查询：{query}
                    
                    请只返回改写后的查询文本，不要添加任何解释或前缀。
                    """;
            PromptTemplate template = new PromptTemplate(promptTemplate);
            Prompt prompt = template.create(Map.of("query", originalQuery));
            ChatResponse response = chatModel.call(prompt);
            String rewritten = response.getResult().getOutput().getText();
            if (StringUtils.isNotBlank(rewritten) && !rewritten.equals(originalQuery)) {
                rewritten = rewritten.trim();
                log.info("查询重写: \"{}\" -> \"{}\"", originalQuery, rewritten);
                return rewritten;
            }
        } catch (Exception e) {
            log.warn("查询重写失败，使用原查询: {}", e.getMessage());
        }
        return originalQuery;
    }

    /**
     * 搜索相关知识（返回详细信息，包括重写后的查询）
     *
     * @param query               查询文本
     * @param topK                返回前 K 个结果
     * @param similarityThreshold 相似度阈值（0.0-1.0），默认 0.4
     * @return 搜索结果信息（包含重写后的查询和文档列表）
     */
    public SearchResult searchWithRewrite(String query, int topK, Double similarityThreshold) {
        if (StringUtils.isBlank(query)) {
            log.warn("搜索查询为空");
            return new SearchResult(query, query, List.of());
        }

        String originalQuery = query.trim();

        // 1. 查询重写：LLM 优化
        String rewrittenQuery = rewriteQuery(originalQuery);

        // 2. 注入 BGE-Small-ZH-v1.5 检索指令前缀 (非常关键，提升部分匹配召回率)
        // 只有在检索时（Query）才加，存库（Document）时不加
        String finalQuery = "为这个句子生成表示以用于检索相关文章：" + rewrittenQuery;

        // 3. 动态阈值逻辑优化
        // BGE-v1.5 的 COSINE 分数通常在 0.5-0.7 波动，0.3 是个合理的召回门槛
        double threshold = (similarityThreshold != null && similarityThreshold >= 0.0)
                ? similarityThreshold : 0.4;

        // 针对超短文本或长重写文本微调阈值
        if (rewrittenQuery.length() < 10) {
            threshold = 0.3;
            log.info("短文本查询，降低相似度阈值至 {}", threshold);
        }

        // 4. HNSW 索引参数适配 (不再使用 nprobe)
        // ef 指搜索时探索的节点数，通常设为 topK 的 2-4 倍，最小建议 64
        int ef = Math.max(topK * 4, 64);

        log.info("开始检索知识库 - 算法配置: HNSW, ef: {}, 相似度阈值: {}", ef, threshold);

        // 5. 构建针对 Milvus HNSW 优化的请求
        MilvusSearchRequest searchRequest = MilvusSearchRequest.milvusBuilder()
                .query(finalQuery)
                .topK(Math.max(topK, 10)) // 扩大取样范围，增加容错
                .similarityThreshold(threshold)
                .searchParamsJson("{\"ef\":" + ef + "}") // HNSW 专用参数
                .build();

        List<Document> results = vectorStore.similaritySearch(searchRequest);
        log.info("搜索完成，召回文档块数量: {}", results.size());
        return new SearchResult(originalQuery, rewrittenQuery, results);
    }

    /**
     * 搜索结果信息
     */
    public record SearchResult(String originalQuery, String rewrittenQuery, List<Document> documents) {
    }

    /**
     * 搜索相关知识（保持向后兼容）
     *
     * @param query               查询文本
     * @param topK                返回前 K 个结果
     * @param similarityThreshold 相似度阈值（0.0-1.0），默认 0.3
     * @return 相关文档列表
     */
    public List<Document> search(String query, int topK, Double similarityThreshold) {
        return searchWithRewrite(query, topK, similarityThreshold).documents();
    }

    /**
     * 按空间 ID 和文档名查询该文档的所有块（用于「查看」预览）。
     * 向量库 metadata 使用 space_id + filename 过滤。
     *
     * @param spaceId  知识空间 ID
     * @param filename 文档名（文件名或文本录入标识）
     * @return 文档块列表
     */
    public List<Document> searchChunksBySpaceIdAndFilename(Long spaceId, String filename) {
        // 1. 构建过滤表达式 (对应物理列 space_id 和 document_name)
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression filterExpression = b.and(
                b.eq("space_id", spaceId),
                b.eq("document_name", filename)
        ).build();

        // 2. 使用 MilvusSearchRequest
        // 虽然是 similaritySearch，但由于阈值为 0 且只有过滤条件，它表现得像精确查询
        MilvusSearchRequest request = MilvusSearchRequest.milvusBuilder()
                .query("") // 向量搜索关键字为空
                .topK(1000) // 假设一个文件不会超过 1000 个分块
                .similarityThreshold(0.0) // 必须为 0，确保不过滤任何分块
                .filterExpression(filterExpression)
                .build();

        List<Document> results = vectorStore.similaritySearch(request);

        // 3. 按照 chunk_index 进行物理排序，还原文档顺序
        return results.stream()
                .sorted(Comparator.comparingInt(doc -> {
                    Object idx = doc.getMetadata().get("chunk_index");
                    return idx instanceof Number ? ((Number) idx).intValue() : 0;
                }))
                .collect(Collectors.toList());
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

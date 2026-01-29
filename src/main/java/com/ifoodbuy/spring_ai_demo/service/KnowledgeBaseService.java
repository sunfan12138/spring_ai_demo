package com.ifoodbuy.spring_ai_demo.service;

import com.ifoodbuy.spring_ai_demo.repository.KnowledgeDocumentRepository;
import com.ifoodbuy.spring_ai_demo.repository.KnowledgeSpaceRepository;
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
    private final ChatModel chatModel;

    public KnowledgeBaseService(VectorStore vectorStore,
                                KnowledgeDocumentRepository knowledgeDocumentRepository,
                                KnowledgeSpaceRepository knowledgeSpaceRepository,
                                ChatModel chatModel) {
        this.vectorStore = vectorStore;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeSpaceRepository = knowledgeSpaceRepository;
        this.chatModel = chatModel;
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
     * @param file     文档文件
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
        String customName = metadata.get("documentName") != null
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
        Map<String, Object> docMetadata = new HashMap<>(metadata);
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
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /**
     * 上传文本内容到知识库
     *
     * @param text     文本内容
     * @param metadata 文档元数据（可选）
     * @return 上传的文档数量
     */
    public int uploadText(String text, Map<String, Object> metadata) {
        log.info("开始上传文本，长度: {}", text.length());

        Long spaceId = parseSpaceId(metadata);
        if (spaceId == null || knowledgeSpaceRepository.findById(spaceId).isEmpty()) {
            throw new IllegalArgumentException("知识空间不存在，请先创建或选择已有空间");
        }
        String docName = metadata.get("source") != null ? metadata.get("source").toString().trim() : null;
        if (docName == null || docName.isBlank()) {
            docName = "文本-" + System.currentTimeMillis();
        }
        if (knowledgeDocumentRepository.existsBySpaceIdAndDocumentName(spaceId, docName)) {
            throw new IllegalArgumentException("该空间下已存在同名文档，请使用其他名称或删除后重试");
        }

        Map<String, Object> docMetadata = new HashMap<>(metadata);
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
     * @param similarityThreshold 相似度阈值（0.0-1.0），默认 0.3
     * @return 搜索结果信息（包含重写后的查询和文档列表）
     */
    public SearchResult searchWithRewrite(String query, int topK, Double similarityThreshold) {
        if (query == null || query.trim().isEmpty()) {
            log.warn("搜索查询为空");
            return new SearchResult(query, query, List.of());
        }

        String originalQuery = query.trim();

        // 查询重写：使用 LLM 优化查询文本
        String rewrittenQuery = rewriteQuery(originalQuery);

        // 默认阈值降低到0.3，以提高召回率（特别是对于部分文本匹配）
        double threshold = similarityThreshold != null && similarityThreshold >= 0.0 && similarityThreshold <= 1.0
                ? similarityThreshold : 0.3;

        // 对于短查询文本，进一步降低阈值以提高召回率
        if (rewrittenQuery.length() < 20) {
            threshold = Math.min(threshold, 0.2); // 短文本使用更低的阈值
            log.info("查询文本较短 ({} 字符)，自动降低阈值到 {}", rewrittenQuery.length(), threshold);
        }

        log.info("搜索知识库，原查询: \"{}\", 重写后: \"{}\" (长度: {}), topK: {}, 相似度阈值: {}",
                originalQuery.length() > 50 ? originalQuery.substring(0, 50) + "..." : originalQuery,
                rewrittenQuery.length() > 50 ? rewrittenQuery.substring(0, 50) + "..." : rewrittenQuery,
                rewrittenQuery.length(), topK, threshold);

        // 使用 MilvusSearchRequest 以支持 Milvus 特定参数
        // 对于 IVF_FLAT 索引，增加 nprobe 参数以提高召回率
        int nprobe = rewrittenQuery.length() < 20 ? 512 : 256;
        MilvusSearchRequest searchRequest = MilvusSearchRequest.milvusBuilder()
                .query(rewrittenQuery) // 使用重写后的查询
                .topK(Math.max(topK * 2, 20)) // 请求更多结果，然后在应用阈值后筛选
                .similarityThreshold(threshold)
                .searchParamsJson("{\"nprobe\":" + nprobe + "}") // 动态调整 nprobe
                .build();

        List<Document> results = vectorStore.similaritySearch(searchRequest);

        // 记录每个结果的距离信息（用于调试）
        if (log.isDebugEnabled() && !results.isEmpty()) {
            for (int i = 0; i < Math.min(results.size(), 3); i++) {
                Document doc = results.get(i);
                Object distance = doc.getMetadata().get("distance");
                if (distance != null) {
                    double dist = ((Number) distance).doubleValue();
                    double sim = 1.0 - dist; // COSINE距离转相似度
                    log.debug("结果 {}: 距离={}, 相似度={}, 内容预览={}",
                            i + 1, dist, sim,
                            doc.getFormattedContent().length() > 50 ? doc.getFormattedContent().substring(0, 50) + "..." : doc.getFormattedContent());
                }
            }
        }

        log.info("搜索完成，找到 {} 个相关文档", results.size());
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

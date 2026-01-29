package com.ifoodbuy.spring_ai_demo.service;

import com.ifoodbuy.spring_ai_demo.repository.KnowledgeDocumentRepository;
import com.ifoodbuy.spring_ai_demo.repository.KnowledgeSpaceRepository;
import io.milvus.client.MilvusClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
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
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private final MilvusClient milvusClient;

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
        // 关键优化：为每个块注入唯一的顺序索引 (chunk_index)
        for (int i = 0; i < processedDocuments.size(); i++) {
            Document doc = processedDocuments.get(i);
            // 复制一份基础元数据，避免所有 doc 共享同一个 Map 导致并发风险（如果有的话）
            Map<String, Object> currentMetadata = new HashMap<>(docMetadata);
            // 注入块索引：从 0 开始
            currentMetadata.put("chunk_index", i);
            // 可选：记录总分块数，方便校验完整性
            currentMetadata.put("total_chunks", processedDocuments.size());
            doc.getMetadata().putAll(currentMetadata);
        }

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
                ? similarityThreshold : 0.35;

        // 针对超短文本或长重写文本微调阈值
        if (rewrittenQuery.length() < 10) {
            threshold = 0.25;
            log.info("短文本查询，降低相似度阈值至 {}", threshold);
        }

        // 4. HNSW 索引参数适配 (不再使用 nprobe)
        // ef 指搜索时探索的节点数，通常设为 topK 的 2-4 倍，最小建议 64
        int ef = Math.max(topK * 4, 64);

        log.info("开始检索知识库 - 算法配置: HNSW, ef: {}, 相似度阈值: {}", ef, threshold);

        // 5. 构建针对 Milvus HNSW 优化的请求
        MilvusSearchRequest searchRequest = MilvusSearchRequest.milvusBuilder()
                .query(finalQuery)
                .topK(Math.max(topK * 3, 30)) // 扩大取样范围，增加容错
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
        String expr = String.format("space_id == %d && filename == '%s'", spaceId, filename);
        QueryParam queryParam = QueryParam.newBuilder()
                .withCollectionName("knowledge_base")
                .withExpr(expr)
                .withOutFields(Arrays.asList("content", "metadata")) // 只查你需要的字段
                .build();

        R<QueryResults> response = milvusClient.query(queryParam);
        // 随后将 QueryResults 转换为 Spring AI 的 Document 对象
        return convertToSpringAIDocuments(response);
    }

    /**
     * 将 Milvus 原生 QueryResults 转换为 Spring AI Document
     */
    private List<Document> convertToSpringAIDocuments(R<QueryResults> response) {
        if (response == null || response.getData() == null) {
            return Collections.emptyList();
        }

        List<Document> documents = new ArrayList<>();

        // 使用 QueryResultsWrapper 方便地按行处理数据
        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        List<QueryResultsWrapper.RowRecord> rows = wrapper.getRowRecords();

        for (QueryResultsWrapper.RowRecord row : rows) {
            // 1. 提取核心内容字段 (假设你在 Milvus 中定义的文本字段名为 "content")
            String content = row.get("content").toString();

            // 2. 提取元数据字段 (Metadata)
            // Spring AI 的 Document 构造函数接受一个 Map<String, Object>
            Map<String, Object> metadata = new HashMap<>();

            // 遍历行中所有的字段，将非内容字段存入 metadata
            row.getFieldValues().forEach((key, value) -> {
                if (!"content".equals(key) && !"embedding".equals(key)) {
                    metadata.put(key, value);
                }
            });

            // 3. 提取 ID (如果有)
            String id = row.get("id") != null ? row.get("id").toString() : UUID.randomUUID().toString();

            // 4. 构建 Spring AI Document 对象
            Document document = new Document(id, content, metadata);
            documents.add(document);
        }

        return documents.stream()
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

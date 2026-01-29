package com.ifoodbuy.spring_ai_demo.repository;

import com.ifoodbuy.spring_ai_demo.entity.KnowledgeDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class KnowledgeDocumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initTable();
        migrateToSpaceIdIfNeeded();
    }

    private void initTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS knowledge_document (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                space_id BIGINT NOT NULL,
                document_name VARCHAR(500) NOT NULL,
                chunk_count INT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                raw_data LONGBLOB,
                file_type VARCHAR(255),
                file_size BIGINT
            )
            """;
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            // 表可能已存在（旧结构）
        }
        addRawDataColumnsIfNeeded();
    }

    /** 若表已存在但无 raw_data/file_type/file_size 列，则添加（仅 MySQL） */
    private void addRawDataColumnsIfNeeded() {
        try {
            Integer hasRawData = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document' AND COLUMN_NAME = 'raw_data'",
                    Integer.class);
            if (hasRawData != null && hasRawData > 0) return;
            jdbcTemplate.execute("ALTER TABLE knowledge_document ADD COLUMN raw_data BLOB");
            jdbcTemplate.execute("ALTER TABLE knowledge_document ADD COLUMN file_type VARCHAR(255)");
            jdbcTemplate.execute("ALTER TABLE knowledge_document ADD COLUMN file_size BIGINT");
        } catch (Exception e) {
            // 非 MySQL 或已存在，忽略
        }
    }

    /** 若表仍为旧结构（含 space 无 space_id），则迁移为 space_id（仅 MySQL） */
    private void migrateToSpaceIdIfNeeded() {
        try {
            Integer hasSpaceId = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document' AND COLUMN_NAME = 'space_id'",
                    Integer.class);
            if (hasSpaceId != null && hasSpaceId > 0) return;
            Integer hasSpace = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document' AND COLUMN_NAME = 'space'",
                    Integer.class);
            if (hasSpace == null || hasSpace == 0) return;
            jdbcTemplate.execute("ALTER TABLE knowledge_document ADD COLUMN space_id BIGINT NULL");
            jdbcTemplate.update("UPDATE knowledge_document d INNER JOIN knowledge_space s ON d.space = s.name SET d.space_id = s.id");
            jdbcTemplate.execute("ALTER TABLE knowledge_document DROP COLUMN space");
            jdbcTemplate.execute("ALTER TABLE knowledge_document MODIFY space_id BIGINT NOT NULL");
        } catch (Exception e) {
            // 非 MySQL 或表已是新结构，忽略
        }
    }

    /** 列表查询用，不加载 raw_data */
    private static final RowMapper<KnowledgeDocument> ROW_MAPPER = (rs, rowNum) ->
            new KnowledgeDocument(
                    rs.getLong("id"),
                    rs.getLong("space_id"),
                    rs.getString("document_name"),
                    rs.getInt("chunk_count"),
                    rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null,
                    null,
                    rs.getString("file_type"),
                    rs.getObject("file_size") != null ? rs.getLong("file_size") : null
            );

    /** 含 raw_data 的完整映射，用于按 id 查询下载 */
    private static final RowMapper<KnowledgeDocument> ROW_MAPPER_FULL = (rs, rowNum) ->
            new KnowledgeDocument(
                    rs.getLong("id"),
                    rs.getLong("space_id"),
                    rs.getString("document_name"),
                    rs.getInt("chunk_count"),
                    rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null,
                    rs.getBytes("raw_data"),
                    rs.getString("file_type"),
                    rs.getObject("file_size") != null ? rs.getLong("file_size") : null
            );

    public void insert(Long spaceId, String documentName, int chunkCount, byte[] rawData, String fileType, Long fileSize) {
        String sql = "INSERT INTO knowledge_document (space_id, document_name, chunk_count, raw_data, file_type, file_size) VALUES (?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, spaceId, documentName, chunkCount, rawData, fileType, fileSize);
    }

    public java.util.Optional<KnowledgeDocument> findById(Long id) {
        if (id == null) return java.util.Optional.empty();
        String sql = "SELECT id, space_id, document_name, chunk_count, created_at, raw_data, file_type, file_size FROM knowledge_document WHERE id = ?";
        List<KnowledgeDocument> list = jdbcTemplate.query(sql, ROW_MAPPER_FULL, id);
        return list.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(list.get(0));
    }

    public List<KnowledgeDocument> findAll() {
        String sql = "SELECT id, space_id, document_name, chunk_count, created_at, file_type, file_size FROM knowledge_document ORDER BY space_id, created_at DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }

    /** 分页查询，按创建时间倒序 */
    public List<KnowledgeDocument> findAll(int offset, int limit) {
        String sql = "SELECT id, space_id, document_name, chunk_count, created_at, file_type, file_size FROM knowledge_document ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, limit, offset);
    }

    public long count() {
        String sql = "SELECT COUNT(*) FROM knowledge_document";
        Long n = jdbcTemplate.queryForObject(sql, Long.class);
        return n != null ? n : 0L;
    }

    /** 按空间 ID 分页查询文档 */
    public List<KnowledgeDocument> findBySpaceId(Long spaceId, int offset, int limit) {
        String sql = "SELECT id, space_id, document_name, chunk_count, created_at, file_type, file_size FROM knowledge_document WHERE space_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, spaceId, limit, offset);
    }

    public long countBySpaceId(Long spaceId) {
        String sql = "SELECT COUNT(*) FROM knowledge_document WHERE space_id = ?";
        Long n = jdbcTemplate.queryForObject(sql, Long.class, spaceId);
        return n != null ? n : 0L;
    }

    /** 空间内文档名是否已存在 */
    public boolean existsBySpaceIdAndDocumentName(Long spaceId, String documentName) {
        if (spaceId == null || documentName == null || documentName.isBlank()) return false;
        String sql = "SELECT 1 FROM knowledge_document WHERE space_id = ? AND document_name = ? LIMIT 1";
        List<Object> list = jdbcTemplate.query(sql, (rs, rowNum) -> 1, spaceId, documentName.trim());
        return !list.isEmpty();
    }
}

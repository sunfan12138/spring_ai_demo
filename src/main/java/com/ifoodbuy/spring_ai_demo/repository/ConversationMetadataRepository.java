package com.ifoodbuy.spring_ai_demo.repository;

import com.ifoodbuy.spring_ai_demo.entity.ConversationMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ConversationMetadataRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConversationMetadataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initTable();
    }

    private static final String NOT_DELETED_CONDITION = " AND (deleted = 0 OR deleted IS NULL)";

    private void initTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS conversation_metadata (
                conversation_id VARCHAR(255) PRIMARY KEY,
                title VARCHAR(500),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                deleted TINYINT(1) DEFAULT 0,
                deleted_at TIMESTAMP NULL
            )
            """;
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            // 表可能已存在，忽略错误
        }
        addDeletedColumnsIfMissing();
    }

    /** 为已有表增加逻辑删除列（兼容旧库） */
    private void addDeletedColumnsIfMissing() {
        try {
            jdbcTemplate.execute("ALTER TABLE conversation_metadata ADD COLUMN deleted TINYINT(1) DEFAULT 0");
        } catch (Exception e) {
            // 列已存在，忽略
        }
        try {
            jdbcTemplate.execute("ALTER TABLE conversation_metadata ADD COLUMN deleted_at TIMESTAMP NULL");
        } catch (Exception e) {
            // 列已存在，忽略
        }
    }

    private final RowMapper<ConversationMetadata> rowMapper = (rs, rowNum) -> {
        ConversationMetadata metadata = new ConversationMetadata();
        metadata.setConversationId(rs.getString("conversation_id"));
        metadata.setTitle(rs.getString("title"));
        if (rs.getTimestamp("created_at") != null) {
            metadata.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        }
        if (rs.getTimestamp("updated_at") != null) {
            metadata.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        }
        if (hasColumn(rs, "deleted")) {
            metadata.setDeleted(rs.getBoolean("deleted"));
        }
        if (hasColumn(rs, "deleted_at") && rs.getTimestamp("deleted_at") != null) {
            metadata.setDeletedAt(rs.getTimestamp("deleted_at").toLocalDateTime());
        }
        return metadata;
    };

    private boolean hasColumn(java.sql.ResultSet rs, String column) {
        try {
            rs.findColumn(column);
            return true;
        } catch (java.sql.SQLException e) {
            return false;
        }
    }

    public void save(ConversationMetadata metadata) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime createdAt = metadata.getCreatedAt() != null ? metadata.getCreatedAt() : now;
        
        // 先尝试查询是否存在（含已逻辑删除，便于恢复）
        Optional<ConversationMetadata> existing = findByIdIncludeDeleted(metadata.getConversationId());
        
        if (existing.isPresent()) {
            // 更新（含恢复逻辑删除：置回 deleted=0, deleted_at=NULL）
            String sql = """
                UPDATE conversation_metadata 
                SET title = ?, updated_at = ?, deleted = 0, deleted_at = NULL
                WHERE conversation_id = ?
                """;
            jdbcTemplate.update(sql, metadata.getTitle(), now, metadata.getConversationId());
        } else {
            // 插入
            String sql = """
                INSERT INTO conversation_metadata (conversation_id, title, created_at, updated_at, deleted)
                VALUES (?, ?, ?, ?, 0)
                """;
            jdbcTemplate.update(sql,
                    metadata.getConversationId(),
                    metadata.getTitle(),
                    createdAt,
                    now
            );
        }
    }

    public Optional<ConversationMetadata> findById(String conversationId) {
        String sql = "SELECT * FROM conversation_metadata WHERE conversation_id = ?" + NOT_DELETED_CONDITION;
        List<ConversationMetadata> results = jdbcTemplate.query(sql, rowMapper, conversationId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** 按 id 查询，包含已逻辑删除的记录（用于 save 时判断是更新/恢复还是新增） */
    public Optional<ConversationMetadata> findByIdIncludeDeleted(String conversationId) {
        String sql = "SELECT * FROM conversation_metadata WHERE conversation_id = ?";
        List<ConversationMetadata> results = jdbcTemplate.query(sql, rowMapper, conversationId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<ConversationMetadata> findAll(String keyword, int offset, int limit) {
        String sql = """
            SELECT * FROM conversation_metadata
            WHERE (? IS NULL OR title LIKE ? OR conversation_id LIKE ?)
            """ + NOT_DELETED_CONDITION + """
            ORDER BY updated_at DESC
            LIMIT ? OFFSET ?
            """;
        String searchPattern = keyword != null && !keyword.trim().isEmpty() 
            ? "%" + keyword.trim() + "%" : null;
        return jdbcTemplate.query(sql, rowMapper, searchPattern, searchPattern, searchPattern, limit, offset);
    }

    public long count(String keyword) {
        String sql = """
            SELECT COUNT(*) FROM conversation_metadata
            WHERE (? IS NULL OR title LIKE ? OR conversation_id LIKE ?)
            """ + NOT_DELETED_CONDITION;
        String searchPattern = keyword != null && !keyword.trim().isEmpty() 
            ? "%" + keyword.trim() + "%" : null;
        Long count = jdbcTemplate.queryForObject(sql, Long.class, searchPattern, searchPattern, searchPattern);
        return count != null ? count : 0;
    }

    /** 逻辑删除：标记 deleted=1 并记录 deleted_at */
    public void deleteById(String conversationId) {
        String sql = """
            UPDATE conversation_metadata
            SET deleted = 1, deleted_at = ?
            WHERE conversation_id = ?
            """;
        jdbcTemplate.update(sql, LocalDateTime.now(), conversationId);
    }

    public void updateTitle(String conversationId, String title) {
        String sql = """
            UPDATE conversation_metadata 
            SET title = ?, updated_at = ?
            WHERE conversation_id = ?
            """ + NOT_DELETED_CONDITION;
        jdbcTemplate.update(sql, title, LocalDateTime.now(), conversationId);
    }

    public void updateTimestamp(String conversationId) {
        String sql = """
            UPDATE conversation_metadata 
            SET updated_at = ?
            WHERE conversation_id = ?
            """ + NOT_DELETED_CONDITION;
        jdbcTemplate.update(sql, LocalDateTime.now(), conversationId);
    }
}

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

    private void initTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS conversation_metadata (
                conversation_id VARCHAR(255) PRIMARY KEY,
                title VARCHAR(500),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            )
            """;
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            // 表可能已存在，忽略错误
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
        return metadata;
    };

    public void save(ConversationMetadata metadata) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime createdAt = metadata.getCreatedAt() != null ? metadata.getCreatedAt() : now;
        
        // 先尝试查询是否存在
        Optional<ConversationMetadata> existing = findById(metadata.getConversationId());
        
        if (existing.isPresent()) {
            // 更新
            String sql = """
                UPDATE conversation_metadata 
                SET title = ?, updated_at = ?
                WHERE conversation_id = ?
                """;
            jdbcTemplate.update(sql, metadata.getTitle(), now, metadata.getConversationId());
        } else {
            // 插入
            String sql = """
                INSERT INTO conversation_metadata (conversation_id, title, created_at, updated_at)
                VALUES (?, ?, ?, ?)
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
        String sql = "SELECT * FROM conversation_metadata WHERE conversation_id = ?";
        List<ConversationMetadata> results = jdbcTemplate.query(sql, rowMapper, conversationId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<ConversationMetadata> findAll(String keyword, int offset, int limit) {
        String sql = """
            SELECT * FROM conversation_metadata
            WHERE (? IS NULL OR title LIKE ? OR conversation_id LIKE ?)
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
            """;
        String searchPattern = keyword != null && !keyword.trim().isEmpty() 
            ? "%" + keyword.trim() + "%" : null;
        Long count = jdbcTemplate.queryForObject(sql, Long.class, searchPattern, searchPattern, searchPattern);
        return count != null ? count : 0;
    }

    public void deleteById(String conversationId) {
        String sql = "DELETE FROM conversation_metadata WHERE conversation_id = ?";
        jdbcTemplate.update(sql, conversationId);
    }

    public void updateTitle(String conversationId, String title) {
        String sql = """
            UPDATE conversation_metadata 
            SET title = ?, updated_at = ?
            WHERE conversation_id = ?
            """;
        jdbcTemplate.update(sql, title, LocalDateTime.now(), conversationId);
    }

    public void updateTimestamp(String conversationId) {
        String sql = """
            UPDATE conversation_metadata 
            SET updated_at = ?
            WHERE conversation_id = ?
            """;
        jdbcTemplate.update(sql, LocalDateTime.now(), conversationId);
    }
}

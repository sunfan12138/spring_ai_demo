package com.ifoodbuy.spring_ai_demo.repository;

import com.ifoodbuy.spring_ai_demo.entity.KnowledgeSpace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class KnowledgeSpaceRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeSpaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initTable();
    }

    private void initTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS knowledge_space (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE KEY uk_name (name)
            )
            """;
        try {
            jdbcTemplate.execute(sql);
            jdbcTemplate.update("INSERT IGNORE INTO knowledge_space (name) VALUES (?)", "默认");
        } catch (Exception e) {
            // 表可能已存在
        }
    }

    private static final RowMapper<KnowledgeSpace> ROW_MAPPER = (rs, rowNum) ->
            new KnowledgeSpace(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null
            );

    public KnowledgeSpace insert(String name) {
        String sql = "INSERT INTO knowledge_space (name) VALUES (?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name != null ? name.trim() : "");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new KnowledgeSpace(key != null ? key.longValue() : null, name, null);
    }

    public List<KnowledgeSpace> findAll() {
        String sql = "SELECT id, name, created_at FROM knowledge_space ORDER BY name";
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }

    public Optional<KnowledgeSpace> findById(long id) {
        String sql = "SELECT id, name, created_at FROM knowledge_space WHERE id = ?";
        List<KnowledgeSpace> list = jdbcTemplate.query(sql, ROW_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<KnowledgeSpace> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String sql = "SELECT id, name, created_at FROM knowledge_space WHERE name = ?";
        List<KnowledgeSpace> list = jdbcTemplate.query(sql, ROW_MAPPER, name.trim());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public boolean existsByName(String name) {
        if (name == null || name.isBlank()) return false;
        String sql = "SELECT 1 FROM knowledge_space WHERE name = ? LIMIT 1";
        List<Object> list = jdbcTemplate.query(sql, (rs, rowNum) -> 1, name.trim());
        return !list.isEmpty();
    }

    /** 更新名称（空间名全局唯一） */
    public int updateName(long id, String newName) {
        String sql = "UPDATE knowledge_space SET name = ? WHERE id = ?";
        return jdbcTemplate.update(sql, newName != null ? newName.trim() : "", id);
    }
}

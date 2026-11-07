package com.yao.traffic.evidence.infrastructure;

import com.yao.traffic.evidence.domain.Evidence;
import com.yao.traffic.shared.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;

@Repository
public class EvidenceRepository {
    private final JdbcTemplate jdbc;
    public EvidenceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Evidence insert(long owner, String key, String sha256, String mediaType, long size) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO evidence (owner_id, object_key, sha256, media_type, size_bytes) VALUES (?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setLong(1, owner); statement.setString(2, key); statement.setString(3, sha256);
            statement.setString(4, mediaType); statement.setLong(5, size);
            return statement;
        }, holder);
        if (holder.getKey() == null) throw new IllegalStateException("Evidence identity was not generated");
        return new Evidence(holder.getKey().longValue(), owner, key, sha256, mediaType, size);
    }

    public Evidence required(long id) {
        return jdbc.query("SELECT * FROM evidence WHERE id = ?", (rs, row) -> new Evidence(rs.getLong("id"),
                rs.getLong("owner_id"), rs.getString("object_key"), rs.getString("sha256"), rs.getString("media_type"),
                rs.getLong("size_bytes")), id).stream().findFirst().orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND", "图片证据不存在"));
    }
}

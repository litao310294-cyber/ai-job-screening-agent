package com.aijob.analyzer.retrieval.vector;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;

/** Atomic replacement of an entire profile projection, loaded without provider calls. */
public class JdbcVectorProjectionStore implements VectorProjectionStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcVectorProjectionStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }
    @Override public void store(ProfileArtifactIdentity identity, List<VectorRecord> records) {
        for (var record : records) {
            if (!identity.equals(record.profileIdentity()) || record.dimension() < 1 || record.dimension() != record.vector().length) {
                throw new IllegalArgumentException("Invalid vector identity or dimensions");
            }
            for (float value : record.vector()) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite vector");
        }
        String payload;
        try { payload = json.writeValueAsString(records); }
        catch (Exception e) { throw new IllegalStateException("Vector serialization failed", e); }
        jdbc.update("INSERT INTO profile_vector_artifact(user_id,profile_version,records_json) VALUES (?,?,?) ON DUPLICATE KEY UPDATE records_json=VALUES(records_json)",
                identity.userId(), identity.profileVersion(), payload);
    }
    @Override public List<VectorRecord> load(ProfileArtifactIdentity identity) {
        var rows = jdbc.query("SELECT records_json FROM profile_vector_artifact WHERE user_id=? AND profile_version=?",
                (rs,n) -> rs.getString(1), identity.userId(), identity.profileVersion());
        if (rows.isEmpty()) return List.of();
        try { return json.readValue(rows.getFirst(), new TypeReference<List<VectorRecord>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Persisted vectors are invalid", e); }
    }
}

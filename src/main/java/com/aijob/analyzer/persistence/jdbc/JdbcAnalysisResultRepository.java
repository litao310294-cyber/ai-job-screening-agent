package com.aijob.analyzer.persistence.jdbc;

import com.aijob.analyzer.analysis.CanonicalCommitResult;
import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
@Profile("!test")
public class JdbcAnalysisResultRepository implements AnalysisResultRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcAnalysisResultRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Override public DbReadResult<AnalysisArtifact> findCanonical(AnalysisIdentity id) { return read(id); }
    @Override public DbReadResult<AnalysisArtifact> readBack(AnalysisIdentity id) { return read(id); }

    @Override @Transactional
    public CanonicalCommitResult commitCanonical(AnalysisIdentity id, JobAnalysisResult result, AnalysisDependencySnapshot snapshot) {
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("INSERT INTO analysis_canonical_result(job_fingerprint,analysis_dependency_fingerprint,dependency_snapshot_json,result_json) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, id.jobFingerprint().value()); ps.setString(2, id.analysisDependencyFingerprint().value()); ps.setString(3, write(snapshot)); ps.setString(4, write(result)); return ps;
            }, keys);
            return new CanonicalCommitResult.Inserted(new AnalysisArtifact(keys.getKey().longValue(), id, snapshot, result, Instant.now()));
        } catch (DuplicateKeyException duplicate) {
            DbReadResult<AnalysisArtifact> existing = read(id);
            if (existing.outcome() == DbReadOutcome.FOUND) return new CanonicalCommitResult.Reused(existing.value());
            return new CanonicalCommitResult.Uncertain("DUPLICATE_READBACK_FAILED");
        } catch (RuntimeException failure) {
            return new CanonicalCommitResult.Uncertain("ANALYSIS_COMMIT_UNCERTAIN");
        }
    }

    private DbReadResult<AnalysisArtifact> read(AnalysisIdentity id) {
        try {
            List<AnalysisArtifact> rows = jdbc.query("SELECT * FROM analysis_canonical_result WHERE job_fingerprint=? AND analysis_dependency_fingerprint=?", (rs, row) -> new AnalysisArtifact(rs.getLong("id"), id, read(rs.getString("dependency_snapshot_json"), AnalysisDependencySnapshot.class), read(rs.getString("result_json"), JobAnalysisResult.class), rs.getTimestamp("created_at").toInstant()), id.jobFingerprint().value(), id.analysisDependencyFingerprint().value());
            return rows.isEmpty() ? new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null) : new DbReadResult<>(DbReadOutcome.FOUND, rows.getFirst(), null);
        } catch (RuntimeException failure) { return new DbReadResult<>(DbReadOutcome.READ_FAILED, null, "ANALYSIS_READ_FAILED"); }
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private <T> T read(String value, Class<T> type) { try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException(e); } }
}

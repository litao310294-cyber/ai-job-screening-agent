package com.aijob.analyzer.persistence.jdbc;

import com.aijob.analyzer.analysis.identity.JobFingerprint;
import com.aijob.analyzer.analysis.identity.ParserDependencyFingerprint;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.requirement.domain.*;
import com.aijob.analyzer.requirement.port.RequirementSetRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
@Profile("!test")
public class JdbcRequirementSetRepository implements RequirementSetRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcRequirementSetRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Override
    public DbReadResult<RequirementSet> find(RequirementIdentity id) {
        try {
            List<RequirementSet> rows = jdbc.query("SELECT * FROM requirement_set WHERE job_fingerprint=? AND parser_dependency_fingerprint=?",
                    (rs, row) -> hydrate(rs.getLong("id"), id, rs.getString("structured_constraints_json"), rs.getString("ignored_context_json")),
                    id.jobFingerprint().value(), id.parserDependencyFingerprint().value());
            return rows.isEmpty() ? new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null) : new DbReadResult<>(DbReadOutcome.FOUND, rows.getFirst(), null);
        } catch (RuntimeException failure) { return new DbReadResult<>(DbReadOutcome.READ_FAILED, null, "REQUIREMENT_READ_FAILED"); }
    }

    @Override @Transactional
    public RequirementSet insertCanonical(ValidatedRequirementPayload payload) {
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("INSERT INTO requirement_set(job_fingerprint,parser_dependency_fingerprint,structured_constraints_json,ignored_context_json) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, payload.identity().jobFingerprint().value()); ps.setString(2, payload.identity().parserDependencyFingerprint().value());
                ps.setString(3, write(payload.structuredConstraints())); ps.setString(4, write(payload.ignoredContext())); return ps;
            }, keys);
            long id = keys.getKey().longValue();
            for (AtomicRequirement requirement : payload.retrievalRequirements()) jdbc.update("INSERT INTO atomic_requirement(requirement_set_id,requirement_id,requirement_type,normalized_text,priority,source_text) VALUES (?,?,?,?,?,?)", id, requirement.requirementId(), requirement.requirementType(), requirement.normalizedText(), requirement.priority().name(), requirement.sourceText());
            return new RequirementSet(id, payload.identity(), payload.structuredConstraints(), payload.retrievalRequirements(), payload.ignoredContext());
        } catch (DuplicateKeyException duplicate) {
            DbReadResult<RequirementSet> existing = find(payload.identity());
            if (existing.outcome() == DbReadOutcome.FOUND) return existing.value();
            throw duplicate;
        }
    }

    private RequirementSet hydrate(long id, RequirementIdentity identity, String constraints, String ignored) {
        List<AtomicRequirement> requirements = jdbc.query("SELECT * FROM atomic_requirement WHERE requirement_set_id=? ORDER BY requirement_id", (rs, row) -> new AtomicRequirement(rs.getString("requirement_id"), rs.getString("requirement_type"), rs.getString("normalized_text"), RequirementPriority.valueOf(rs.getString("priority")), rs.getString("source_text")), id);
        return new RequirementSet(id, identity, read(constraints, new TypeReference<List<StructuredConstraint>>() {}), requirements, read(ignored, new TypeReference<List<String>>() {}));
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private <T> T read(String value, TypeReference<T> type) { try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException(e); } }
}

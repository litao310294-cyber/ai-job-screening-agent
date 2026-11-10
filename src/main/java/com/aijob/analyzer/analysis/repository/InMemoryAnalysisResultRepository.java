package com.aijob.analyzer.analysis.repository;

import com.aijob.analyzer.analysis.CanonicalCommitResult;
import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceRecorder;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("test")
public class InMemoryAnalysisResultRepository implements AnalysisResultRepository {

    private final Map<AnalysisIdentity, AnalysisArtifact> artifacts = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong idSequence = new java.util.concurrent.atomic.AtomicLong(3000);

    @Override
    public DbReadResult<AnalysisArtifact> findCanonical(AnalysisIdentity id) {
        AnalysisArtifact artifact = artifacts.get(id);
        if (artifact == null) {
            return new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null);
        }
        return new DbReadResult<>(DbReadOutcome.FOUND, artifact, null);
    }

    @Override
    public CanonicalCommitResult commitCanonical(
            AnalysisIdentity id,
            JobAnalysisResult value,
            AnalysisDependencySnapshot snapshot) {

        AnalysisArtifact artifact = new AnalysisArtifact(
                idSequence.incrementAndGet(),
                id,
                snapshot,
                value,
                Instant.now()
        );
        artifacts.put(id, artifact);
        return new CanonicalCommitResult.Inserted(artifact);
    }

    @Override
    public DbReadResult<AnalysisArtifact> readBack(AnalysisIdentity id) {
        return findCanonical(id);
    }
}

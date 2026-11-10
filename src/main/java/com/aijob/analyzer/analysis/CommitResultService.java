package com.aijob.analyzer.analysis;

import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceRecorder;

import java.util.Objects;

/** Resolves uncertain DB acknowledgements without returning to the LLM boundary. */
public final class CommitResultService {
    private final AnalysisResultRepository repository;

    public CommitResultService(AnalysisResultRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public AnalysisArtifact commit(AnalysisIdentity identity, JobAnalysisResult result, AnalysisDependencySnapshot snapshot, TraceContext trace, TraceRecorder recorder) {
        CanonicalCommitResult commit = repository.commitCanonical(identity, result, snapshot, trace, recorder);
        if (commit instanceof CanonicalCommitResult.Inserted inserted) return inserted.artifact();
        if (commit instanceof CanonicalCommitResult.Reused reused) return reused.artifact();
        return readBack(identity, ((CanonicalCommitResult.Uncertain) commit).errorCode());
    }

    private AnalysisArtifact readBack(AnalysisIdentity identity, String errorCode) {
        var readBack = repository.readBack(identity);
        if (readBack.outcome() == DbReadOutcome.FOUND) return readBack.value();
        if (readBack.outcome() == DbReadOutcome.READ_FAILED) {
            throw new CommitUncertainException(readBack.errorCode());
        }
        throw new CommitUncertainException(errorCode);
    }

    public static final class CommitUncertainException extends RuntimeException {
        public CommitUncertainException(String errorCode) { super(errorCode); }
    }
}

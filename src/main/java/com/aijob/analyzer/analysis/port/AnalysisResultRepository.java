package com.aijob.analyzer.analysis.port;

import com.aijob.analyzer.analysis.CanonicalCommitResult;
import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceRecorder;

public interface AnalysisResultRepository {
    DbReadResult<AnalysisArtifact> findCanonical(AnalysisIdentity id);

    CanonicalCommitResult commitCanonical(
            AnalysisIdentity id,
            JobAnalysisResult value,
            AnalysisDependencySnapshot snapshot);

    default CanonicalCommitResult commitCanonical(AnalysisIdentity id, JobAnalysisResult value,
            AnalysisDependencySnapshot snapshot, TraceContext ignoredTrace, TraceRecorder ignoredRecorder) {
        return commitCanonical(id, value, snapshot);
    }

    DbReadResult<AnalysisArtifact> readBack(AnalysisIdentity id);
}

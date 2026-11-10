package com.aijob.analyzer.analysis.domain;

import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import java.time.Instant;

public record AnalysisArtifact(
        long artifactId,
        AnalysisIdentity identity,
        AnalysisDependencySnapshot dependencySnapshot,
        JobAnalysisResult result,
        Instant createdAt) {
    public AnalysisArtifact {
        if (artifactId <= 0) throw new IllegalArgumentException("artifactId must be positive");
        if (identity == null) throw new IllegalArgumentException("identity cannot be null");
        if (dependencySnapshot == null) throw new IllegalArgumentException("dependencySnapshot cannot be null");
        if (result == null) throw new IllegalArgumentException("result cannot be null");
        if (createdAt == null) throw new IllegalArgumentException("createdAt cannot be null");
    }
}

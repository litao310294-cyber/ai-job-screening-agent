package com.aijob.analyzer.analysis.identity;

public record AnalysisIdentity(
        JobFingerprint jobFingerprint,
        AnalysisDependencyFingerprint analysisDependencyFingerprint) {
    public AnalysisIdentity {
        if (jobFingerprint == null) throw new IllegalArgumentException("jobFingerprint cannot be null");
        if (analysisDependencyFingerprint == null) throw new IllegalArgumentException("analysisDependencyFingerprint cannot be null");
    }
}

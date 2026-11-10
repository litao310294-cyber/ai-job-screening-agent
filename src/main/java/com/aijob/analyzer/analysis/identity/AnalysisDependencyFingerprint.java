package com.aijob.analyzer.analysis.identity;

public record AnalysisDependencyFingerprint(String value) {
    public AnalysisDependencyFingerprint {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("fingerprint value cannot be null or blank");
    }
}

package com.aijob.analyzer.analysis.identity;

public record ParserDependencyFingerprint(String value) {
    public ParserDependencyFingerprint {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("fingerprint value cannot be null or blank");
    }
}

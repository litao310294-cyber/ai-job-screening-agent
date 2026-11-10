package com.aijob.analyzer.analysis.identity;

public record RetrievalSemanticFingerprint(String value) {
    public RetrievalSemanticFingerprint {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("fingerprint value cannot be null or blank");
    }
}

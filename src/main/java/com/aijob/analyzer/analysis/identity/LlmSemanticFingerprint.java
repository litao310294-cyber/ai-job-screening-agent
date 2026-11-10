package com.aijob.analyzer.analysis.identity;

public record LlmSemanticFingerprint(String value) {
    public LlmSemanticFingerprint {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("fingerprint value cannot be null or blank");
    }
}

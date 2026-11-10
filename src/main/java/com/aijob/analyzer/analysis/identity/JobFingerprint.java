package com.aijob.analyzer.analysis.identity;

public record JobFingerprint(String value) {
    public JobFingerprint {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("fingerprint value cannot be null or blank");
    }
}

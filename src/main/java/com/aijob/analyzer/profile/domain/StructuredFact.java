package com.aijob.analyzer.profile.domain;

public record StructuredFact(
        String factType,
        String normalizedValue,
        SourceReference source) {
}

package com.aijob.analyzer.profile.domain;

public record StructuredFactDraft(
        String factType,
        String normalizedValue,
        SourceReference source) {
}

package com.aijob.analyzer.profile.domain;

public record RetrievalFactDraft(
        String factType,
        String normalizedText,
        SourceReference source) {
}

package com.aijob.analyzer.profile.domain;

public record ContextOnlyDraft(
        String category,
        String text,
        SourceReference source) {
}

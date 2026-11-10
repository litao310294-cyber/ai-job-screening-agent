package com.aijob.analyzer.profile.domain;

public record ContextOnlyFact(
        String category,
        String text,
        SourceReference source) {
}

package com.aijob.analyzer.profile.domain;

public record SourceReference(
        String sourceId,
        String sourceTitle,
        String sourceSection,
        String sourceText) {
}

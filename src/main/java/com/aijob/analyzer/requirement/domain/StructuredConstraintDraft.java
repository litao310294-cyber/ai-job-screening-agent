package com.aijob.analyzer.requirement.domain;

public record StructuredConstraintDraft(
        String type,
        String operator,
        String value,
        String sourceText) {
}

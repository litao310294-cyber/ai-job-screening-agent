package com.aijob.analyzer.requirement.domain;

public record StructuredConstraint(
        String type,
        String operator,
        String value,
        String sourceText) {
}

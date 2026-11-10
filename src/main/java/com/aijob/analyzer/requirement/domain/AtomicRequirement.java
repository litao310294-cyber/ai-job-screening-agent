package com.aijob.analyzer.requirement.domain;

public record AtomicRequirement(
        String requirementId,
        String requirementType,
        String normalizedText,
        RequirementPriority priority,
        String sourceText) {
    public AtomicRequirement {
        if (requirementId == null || requirementId.isBlank()) throw new IllegalArgumentException("requirementId cannot be null or blank");
        if (requirementType == null || requirementType.isBlank()) throw new IllegalArgumentException("requirementType cannot be null or blank");
        if (normalizedText == null || normalizedText.isBlank()) throw new IllegalArgumentException("normalizedText cannot be null or blank");
        if (priority == null) throw new IllegalArgumentException("priority cannot be null");
        if (sourceText == null) throw new IllegalArgumentException("sourceText cannot be null");
    }
}

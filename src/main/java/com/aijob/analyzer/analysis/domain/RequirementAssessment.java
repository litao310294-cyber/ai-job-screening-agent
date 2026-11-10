package com.aijob.analyzer.analysis.domain;

import java.util.List;
import java.util.Objects;

public record RequirementAssessment(
        String requirementId,
        RequirementAssessmentStatus status,
        List<String> evidenceRefs,
        String reasoning,
        String gap) {
    public RequirementAssessment {
        if (requirementId == null || requirementId.isBlank()) throw new IllegalArgumentException("requirementId cannot be null or blank");
        Objects.requireNonNull(status);
        if (evidenceRefs == null) {
            evidenceRefs = List.of();
        }
        // Rule: SUPPORTED/PARTIALLY_SUPPORTED/UNSUPPORTED requires at least one evidenceRef
        if (status != RequirementAssessmentStatus.INSUFFICIENT_EVIDENCE && evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("status " + status + " requires at least one evidenceRef");
        }
    }
}

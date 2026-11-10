package com.aijob.analyzer.requirement.domain;

public record RetrievalRequirementDraft(
        String temporaryRequirementId,
        String requirementType,
        String normalizedText,
        RequirementPriority priority,
        String sourceText) {
}

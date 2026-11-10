package com.aijob.analyzer.requirement.domain;

import java.util.List;

public record JobRequirementDraft(
        List<StructuredConstraintDraft> structuredConstraints,
        List<RetrievalRequirementDraft> retrievalRequirements,
        List<String> ignoredContext) {
}

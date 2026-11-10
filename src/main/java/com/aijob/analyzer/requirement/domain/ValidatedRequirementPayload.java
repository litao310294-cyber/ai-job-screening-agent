package com.aijob.analyzer.requirement.domain;

import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import java.util.List;

public record ValidatedRequirementPayload(
        RequirementIdentity identity,
        List<StructuredConstraint> structuredConstraints,
        List<AtomicRequirement> retrievalRequirements,
        List<String> ignoredContext) {
    public ValidatedRequirementPayload {
        if (identity == null) throw new IllegalArgumentException("identity cannot be null");
        if (structuredConstraints == null) structuredConstraints = List.of();
        if (retrievalRequirements == null) retrievalRequirements = List.of();
        if (ignoredContext == null) ignoredContext = List.of();
    }
}

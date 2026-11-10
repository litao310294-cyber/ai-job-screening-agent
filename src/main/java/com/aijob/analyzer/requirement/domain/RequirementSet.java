package com.aijob.analyzer.requirement.domain;

import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import java.util.List;

public record RequirementSet(
        long artifactId,
        RequirementIdentity identity,
        List<StructuredConstraint> structuredConstraints,
        List<AtomicRequirement> retrievalRequirements,
        List<String> ignoredContext) {
    public RequirementSet {
        if (artifactId <= 0) throw new IllegalArgumentException("artifactId must be positive");
        if (identity == null) throw new IllegalArgumentException("identity cannot be null");
        if (structuredConstraints == null) structuredConstraints = List.of();
        if (retrievalRequirements == null) retrievalRequirements = List.of();
        if (ignoredContext == null) ignoredContext = List.of();
    }
}

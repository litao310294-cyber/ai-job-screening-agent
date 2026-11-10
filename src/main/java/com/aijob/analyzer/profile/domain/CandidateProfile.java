package com.aijob.analyzer.profile.domain;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import java.util.List;

public record CandidateProfile(
        long artifactId,
        ProfileArtifactIdentity identity,
        String resumeFingerprint,
        String parserDependencyFingerprint,
        ProfileStatus status,
        List<StructuredFact> structuredFacts,
        List<ProfileFact> retrievalFacts,
        List<ContextOnlyFact> contextOnly) {
    public CandidateProfile {
        if (artifactId <= 0) throw new IllegalArgumentException("artifactId must be positive");
        if (identity == null) throw new IllegalArgumentException("identity cannot be null");
        if (resumeFingerprint == null || resumeFingerprint.isBlank()) throw new IllegalArgumentException("resumeFingerprint cannot be null or blank");
        if (parserDependencyFingerprint == null || parserDependencyFingerprint.isBlank()) throw new IllegalArgumentException("parserDependencyFingerprint cannot be null or blank");
        if (status == null) throw new IllegalArgumentException("status cannot be null");
        if (structuredFacts == null) structuredFacts = List.of();
        if (retrievalFacts == null) retrievalFacts = List.of();
        if (contextOnly == null) contextOnly = List.of();
    }
}

package com.aijob.analyzer.profile.domain;

public record ProfileFact(
        String factId,
        long profileArtifactId,
        String factType,
        String normalizedText,
        SourceReference source) {
    public ProfileFact {
        if (factId == null || factId.isBlank()) throw new IllegalArgumentException("factId cannot be null or blank");
        if (profileArtifactId <= 0) throw new IllegalArgumentException("profileArtifactId must be positive");
        if (factType == null || factType.isBlank()) throw new IllegalArgumentException("factType cannot be null or blank");
        if (normalizedText == null) throw new IllegalArgumentException("normalizedText cannot be null");
        if (source == null) throw new IllegalArgumentException("source cannot be null");
    }
}

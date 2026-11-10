package com.aijob.analyzer.analysis.identity;

public record ProfileArtifactIdentity(String userId, long profileVersion) {
    public ProfileArtifactIdentity {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId cannot be null or blank");
        if (profileVersion <= 0) throw new IllegalArgumentException("profileVersion must be positive");
    }
}

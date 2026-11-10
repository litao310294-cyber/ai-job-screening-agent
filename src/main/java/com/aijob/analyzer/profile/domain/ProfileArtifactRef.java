package com.aijob.analyzer.profile.domain;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;

public record ProfileArtifactRef(
        long artifactId,
        ProfileArtifactIdentity identity) {
}

package com.aijob.analyzer.retrieval.domain;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.profile.domain.SourceReference;
import java.util.Map;

public record ProfileChunk(
        String chunkId,
        ProfileArtifactIdentity profileIdentity,
        String factId,
        String sourceType,
        String content,
        SourceReference source,
        Map<String, String> metadata) {
}

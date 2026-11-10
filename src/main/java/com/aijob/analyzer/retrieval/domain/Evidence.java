package com.aijob.analyzer.retrieval.domain;

import com.aijob.analyzer.profile.domain.SourceReference;

public record Evidence(
        String evidenceRef,
        String requirementId,
        String factId,
        String content,
        SourceReference source) {
    public Evidence {
        if (evidenceRef == null || evidenceRef.isBlank()) throw new IllegalArgumentException("evidenceRef");
        if (requirementId == null || requirementId.isBlank()) throw new IllegalArgumentException("requirementId");
        if (factId == null || factId.isBlank()) throw new IllegalArgumentException("factId");
        if (content == null) throw new IllegalArgumentException("content");
        if (source == null) throw new IllegalArgumentException("source");

        // V1 convention: evidenceRef = factId
        if (!evidenceRef.equals(factId)) {
            throw new IllegalArgumentException("V1 convention: evidenceRef must equal factId");
        }
    }
}

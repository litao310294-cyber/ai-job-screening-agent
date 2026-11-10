package com.aijob.analyzer.retrieval.vector;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;

public record VectorRecord(
        String chunkId,
        String factId,
        ProfileArtifactIdentity profileIdentity,
        String embeddingModelId,
        String providerExposedModelVersion,
        int dimension,
        float[] vector) {
}

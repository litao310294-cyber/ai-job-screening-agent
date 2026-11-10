package com.aijob.analyzer.retrieval.port;

public record EmbeddingResponse(
        float[] vector,
        String modelId,
        String providerExposedModelVersion,
        Integer inputTokens) {
}

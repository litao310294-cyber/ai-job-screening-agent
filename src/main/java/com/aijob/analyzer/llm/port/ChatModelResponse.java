package com.aijob.analyzer.llm.port;

public record ChatModelResponse(
        String rawContent,
        String providerRequestId,
        String modelId,
        String providerExposedModelVersion,
        Integer inputTokens,
        Integer outputTokens,
        String finishReason) {
}

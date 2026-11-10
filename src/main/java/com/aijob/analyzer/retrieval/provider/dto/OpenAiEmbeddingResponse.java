package com.aijob.analyzer.retrieval.provider.dto;

import java.util.List;

public record OpenAiEmbeddingResponse(
        String object,
        List<OpenAiEmbeddingData> data,
        String model,
        OpenAiEmbeddingUsage usage) {
}

package com.aijob.analyzer.retrieval.provider.dto;

import java.util.List;

public record OpenAiEmbeddingRequest(
        String model,
        List<String> input) {
}

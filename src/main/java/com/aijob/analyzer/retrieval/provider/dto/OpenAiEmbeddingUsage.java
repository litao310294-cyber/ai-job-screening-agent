package com.aijob.analyzer.retrieval.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OpenAiEmbeddingUsage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("total_tokens") int totalTokens) {
}

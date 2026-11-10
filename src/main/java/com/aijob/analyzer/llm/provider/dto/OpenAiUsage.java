package com.aijob.analyzer.llm.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OpenAiUsage(
        @JsonProperty("prompt_tokens") Integer promptTokens,
        @JsonProperty("completion_tokens") Integer completionTokens,
        @JsonProperty("total_tokens") Integer totalTokens) {}

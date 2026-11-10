package com.aijob.analyzer.llm.provider.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record OpenAiChatRequest(
        String model,
        List<OpenAiChatMessage> messages,
        BigDecimal temperature,
        @com.fasterxml.jackson.annotation.JsonProperty("top_p") BigDecimal topP,
        @com.fasterxml.jackson.annotation.JsonProperty("max_tokens") Integer maxTokens,
        @com.fasterxml.jackson.annotation.JsonProperty("response_format") Map<String, String> responseFormat) {}

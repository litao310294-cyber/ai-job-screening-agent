package com.aijob.analyzer.llm.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OpenAiChatChoice(
        Integer index,
        OpenAiChatMessage message,
        @JsonProperty("finish_reason") String finishReason) {}

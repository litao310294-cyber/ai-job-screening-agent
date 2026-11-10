package com.aijob.analyzer.llm.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OpenAiChatResponse(
        String id,
        String object,
        Long created,
        String model,
        @JsonProperty("system_fingerprint") String systemFingerprint,
        List<OpenAiChatChoice> choices,
        OpenAiUsage usage) {}

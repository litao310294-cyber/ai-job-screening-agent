package com.aijob.analyzer.llm;

import java.math.BigDecimal;

public record LlmGenerationConfigProperties(
        BigDecimal temperature,
        BigDecimal topP,
        Integer maxOutputTokens,
        String responseFormat) {

    public LlmGenerationConfigSnapshot toSnapshot() {
        return new LlmGenerationConfigSnapshot(temperature, topP, maxOutputTokens, responseFormat);
    }
}

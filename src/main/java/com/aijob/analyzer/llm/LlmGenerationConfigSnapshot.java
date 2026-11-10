package com.aijob.analyzer.llm;

import java.math.BigDecimal;

public record LlmGenerationConfigSnapshot(
        BigDecimal temperature,
        BigDecimal topP,
        Integer maxOutputTokens,
        String responseFormat) {
}

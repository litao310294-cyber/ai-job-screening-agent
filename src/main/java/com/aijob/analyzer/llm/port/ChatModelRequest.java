package com.aijob.analyzer.llm.port;

import com.aijob.analyzer.llm.LlmSemanticSnapshot;

public record ChatModelRequest(
        LlmSemanticSnapshot semanticSnapshot,
        String systemPrompt,
        String userPrompt) {
}

package com.aijob.analyzer.llm;

public record LlmSemanticSnapshot(
        String promptVersion,
        String modelIdentity,
        String schemaVersion,
        LlmGenerationConfigSnapshot generationConfig) {
}

package com.aijob.analyzer.llm;

import com.aijob.analyzer.llm.LlmSemanticSnapshot;

public record LlmExecutionContext(
        LlmSemanticSnapshot semanticSnapshot,
        int attempt,
        int repairCount) {
}

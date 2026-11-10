package com.aijob.analyzer.analysis.identity;

import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;
import com.aijob.analyzer.llm.LlmSemanticSnapshot;

public record AnalysisDependencySnapshot(
        JobFingerprint jobFingerprint,
        long profileArtifactId,
        ProfileArtifactIdentity profileArtifactIdentity,
        long requirementSetId,
        RequirementIdentity requirementSetIdentity,
        String ruleVersion,
        RetrievalSemanticFingerprint retrievalSemanticFingerprint,
        RetrievalSemanticSnapshot retrievalSemanticSnapshot,
        LlmSemanticFingerprint finalLlmSemanticFingerprint,
        LlmSemanticSnapshot finalLlmSemanticSnapshot) {
}

package com.aijob.analyzer.analysis.identity;

import com.aijob.analyzer.llm.LlmSemanticSnapshot;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;

public final class AnalysisIdentityFactory {
    public AnalysisIdentity create(AnalysisDependencySnapshot snapshot) {
        return new AnalysisIdentity(snapshot.jobFingerprint(), new AnalysisDependencyFingerprint(
                RequirementIdentityFactory.sha256(payload(snapshot))
        ));
    }

    public RetrievalSemanticFingerprint retrievalFingerprint(RetrievalSemanticSnapshot snapshot) {
        return new RetrievalSemanticFingerprint(RequirementIdentityFactory.sha256(retrievalPayload(snapshot)));
    }

    public LlmSemanticFingerprint finalLlmFingerprint(LlmSemanticSnapshot snapshot) {
        return new LlmSemanticFingerprint(RequirementIdentityFactory.sha256(RequirementIdentityFactory.parserPayload(snapshot)));
    }

    private String payload(AnalysisDependencySnapshot snapshot) {
        return "profileArtifactIdentity=" + snapshot.profileArtifactIdentity().userId() + ":" + snapshot.profileArtifactIdentity().profileVersion() + "\n"
                + "requirementIdentity=" + snapshot.requirementSetIdentity().jobFingerprint().value() + ":" + snapshot.requirementSetIdentity().parserDependencyFingerprint().value() + "\n"
                + "ruleVersion=" + RequirementIdentityFactory.normalized(snapshot.ruleVersion()) + "\n"
                + "retrievalSemanticFingerprint=" + snapshot.retrievalSemanticFingerprint().value() + "\n"
                + "finalLlmSemanticFingerprint=" + snapshot.finalLlmSemanticFingerprint().value();
    }

    private String retrievalPayload(RetrievalSemanticSnapshot snapshot) {
        return "queryBuilderVersion=" + RequirementIdentityFactory.normalized(snapshot.queryBuilderVersion()) + "\n"
                + "analyzerId=" + RequirementIdentityFactory.normalized(snapshot.analyzerId()) + "\n"
                + "analyzerVersion=" + RequirementIdentityFactory.normalized(snapshot.analyzerVersion()) + "\n"
                + "embeddingModelIdentity=" + RequirementIdentityFactory.normalized(snapshot.embeddingModelIdentity()) + "\n"
                + "keywordCandidateLimit=" + snapshot.keywordCandidateLimit() + "\n"
                + "vectorCandidateLimit=" + snapshot.vectorCandidateLimit() + "\n"
                + "rrfK=" + snapshot.rrfK() + "\n"
                + "finalEvidenceTopK=" + snapshot.finalEvidenceTopK();
    }
}

package com.aijob.analyzer.retrieval;

public record RetrievalSemanticSnapshot(
        String queryBuilderVersion,
        String analyzerId,
        String analyzerVersion,
        String embeddingModelIdentity,
        int keywordCandidateLimit,
        int vectorCandidateLimit,
        int rrfK,
        int finalEvidenceTopK) {
}

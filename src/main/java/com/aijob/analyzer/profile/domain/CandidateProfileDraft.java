package com.aijob.analyzer.profile.domain;

import java.util.List;

public record CandidateProfileDraft(
        List<StructuredFactDraft> structuredFacts,
        List<RetrievalFactDraft> retrievalFacts,
        List<ContextOnlyDraft> contextOnly) {
}

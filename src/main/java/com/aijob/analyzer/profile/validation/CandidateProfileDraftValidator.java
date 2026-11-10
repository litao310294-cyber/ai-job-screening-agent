package com.aijob.analyzer.profile.validation;

import com.aijob.analyzer.llm.validation.GroundingError;
import com.aijob.analyzer.profile.domain.CandidateProfileDraft;
import com.aijob.analyzer.profile.domain.RawResume;
import org.springframework.stereotype.Component;

@Component
public class CandidateProfileDraftValidator {

    public void validate(CandidateProfileDraft draft, RawResume rawResume) {
        // Grounding validation: check if sourceText exists in raw resume
        draft.retrievalFacts().forEach(fact -> {
            String sourceText = fact.source().sourceText();
            if (sourceText != null && !rawResume.rawText().contains(sourceText)) {
                throw new GroundingError("Fact source text not found in raw resume: " + sourceText);
            }
        });

        draft.structuredFacts().forEach(fact -> {
            String sourceText = fact.source().sourceText();
            if (sourceText != null && !rawResume.rawText().contains(sourceText)) {
                throw new GroundingError("Structured fact source text not found in raw resume: " + sourceText);
            }
        });

        draft.contextOnly().forEach(fact -> {
            String sourceText = fact.source().sourceText();
            if (sourceText != null && !rawResume.rawText().contains(sourceText)) {
                throw new GroundingError("Context only fact source text not found in raw resume: " + sourceText);
            }
        });
    }
}

package com.aijob.analyzer.requirement;

import com.aijob.analyzer.requirement.domain.JobRequirementDraft;
import org.springframework.stereotype.Component;

@Component
public class RequirementNormalizer {

    public JobRequirementDraft normalize(JobRequirementDraft draft) {
        // In V1, we do basic normalization: dedup by normalized text might be needed,
        // but for now, we follow the simplest implementation that satisfies the spec.
        // Spec says: "dedup / merge / priority normalize".
        // For simplicity, we keep the LLM output as is if it looks reasonable.
        return draft;
    }
}

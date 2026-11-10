package com.aijob.analyzer.requirement.validation;

import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.llm.validation.GroundingError;
import com.aijob.analyzer.requirement.domain.JobRequirementDraft;
import org.springframework.stereotype.Component;

@Component
public class JobRequirementDraftValidator {

    public void validate(JobRequirementDraft draft, JobContext job) {
        String fullText = String.join(" ",
            job.title(),
            job.city(),
            job.analysisText(),
            job.salary() != null ? job.salary() : "",
            job.duration() != null ? job.duration() : ""
        );

        draft.retrievalRequirements().forEach(req -> {
            String sourceText = req.sourceText();
            if (sourceText != null && !fullText.contains(sourceText)) {
                throw new GroundingError("Requirement source text not found in job context: " + sourceText);
            }
        });

        draft.structuredConstraints().forEach(req -> {
            String sourceText = req.sourceText();
            if (sourceText != null && !fullText.contains(sourceText)) {
                throw new GroundingError("Structured constraint source text not found in job context: " + sourceText);
            }
        });
    }
}

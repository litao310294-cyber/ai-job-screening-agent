package com.aijob.analyzer.workflow;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.domain.JobDecision;
import com.aijob.analyzer.analysis.domain.RequirementAssessment;
import com.aijob.analyzer.analysis.domain.RequirementAssessmentStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class DefaultFallbackResultFactory implements FallbackResultFactory {

    @Override
    public JobAnalysisResult fromRuleAndEvidence(AnalysisContext context) {
        List<RequirementAssessment> assessments = context.requirementSet().retrievalRequirements().stream()
                .map(req -> {
                    List<String> refs = List.of();
                    if (context.retrievalResult() != null) {
                        var evidence = context.retrievalResult().evidenceByRequirement().get(req.requirementId());
                        if (evidence != null) {
                            refs = evidence.stream().map(com.aijob.analyzer.retrieval.domain.Evidence::evidenceRef).toList();
                        }
                    }
                    return new RequirementAssessment(
                            req.requirementId(),
                            RequirementAssessmentStatus.INSUFFICIENT_EVIDENCE,
                            refs,
                            "Fallback due to analysis failure",
                            ""
                    );
                })
                .collect(Collectors.toList());

        return new JobAnalysisResult(
                JobDecision.INSUFFICIENT_EVIDENCE,
                "Analysis failed to produce a reliable assessment. Reverting to safe default.",
                assessments,
                List.of(),
                List.of(),
                "Review manually.",
                "System fallback applied."
        );
    }
}

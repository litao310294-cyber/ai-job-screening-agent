package com.aijob.analyzer.analysis.validation;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.domain.RequirementAssessmentStatus;
import com.aijob.analyzer.llm.validation.BusinessValidationError;
import com.aijob.analyzer.llm.validation.GroundingError;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.retrieval.domain.Evidence;
import com.aijob.analyzer.retrieval.domain.RetrievalResult;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JobAnalysisResultValidator {

    public void validate(JobAnalysisResult result, RequirementSet requirementSet, RetrievalResult retrievalResult) {
        Set<String> allowedRequirementIds = requirementSet.retrievalRequirements().stream()
                .map(req -> req.requirementId())
                .collect(Collectors.toSet());

        Set<String> assessedIds = new HashSet<>();
        result.requirementAssessments().forEach(assessment -> {
            if (!assessedIds.add(assessment.requirementId())) {
                throw new BusinessValidationError("Duplicate requirementId: " + assessment.requirementId());
            }
            // 1. requirementId exists
            if (!allowedRequirementIds.contains(assessment.requirementId())) {
                throw new BusinessValidationError("Unknown requirementId: " + assessment.requirementId());
            }

            // 2. evidenceRef grounding
            List<Evidence> boundEvidence = retrievalResult.evidenceByRequirement().get(assessment.requirementId());
            Set<String> allowedEvidenceRefs = boundEvidence != null ?
                    boundEvidence.stream().map(e -> e.evidenceRef()).collect(Collectors.toSet()) :
                    new HashSet<>();

            assessment.evidenceRefs().forEach(ref -> {
                if (!allowedEvidenceRefs.contains(ref)) {
                    throw new GroundingError("Unknown evidenceRef: " + ref + " for requirement: " + assessment.requirementId());
                }
            });

            // 3. Status invariant: SUPPORTED/PARTIALLY_SUPPORTED/UNSUPPORTED require >= 1 evidenceRef
            if (assessment.status() == RequirementAssessmentStatus.SUPPORTED ||
                assessment.status() == RequirementAssessmentStatus.PARTIALLY_SUPPORTED ||
                assessment.status() == RequirementAssessmentStatus.UNSUPPORTED) {
                if (assessment.evidenceRefs().isEmpty()) {
                    throw new BusinessValidationError("Status " + assessment.status() + " requires at least one evidenceRef for requirement: " + assessment.requirementId());
                }
            }
        });
        if (!assessedIds.equals(allowedRequirementIds)) {
            throw new BusinessValidationError("Every retrieval requirement must have exactly one assessment");
        }
    }
}

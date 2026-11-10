package com.aijob.analyzer.analysis.domain;

import java.util.List;
import java.util.Objects;

public record JobAnalysisResult(
        JobDecision decision,
        String overallAssessment,
        List<RequirementAssessment> requirementAssessments,
        List<String> risks,
        List<String> strengths,
        String recommendation,
        String summary) {
    public JobAnalysisResult {
        Objects.requireNonNull(decision);
        if (requirementAssessments == null) requirementAssessments = List.of();
        if (risks == null) risks = List.of();
        if (strengths == null) strengths = List.of();
    }
}

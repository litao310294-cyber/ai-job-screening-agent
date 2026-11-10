package com.aijob.analyzer.workflow.domain;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;

public record AnalysisExecution(
        WorkflowStatus status,
        ResultOrigin resultOrigin,
        JobAnalysisResult result,
        String degradedReason) {
}

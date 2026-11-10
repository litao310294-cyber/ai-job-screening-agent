package com.aijob.analyzer.workflow.port;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.workflow.AnalysisContext;
import com.aijob.analyzer.workflow.domain.WorkflowError;

import java.util.Optional;

@FunctionalInterface
public interface ResultValidator {
    Optional<WorkflowError> validate(JobAnalysisResult candidate, AnalysisContext context);
}

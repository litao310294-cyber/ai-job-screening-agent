package com.aijob.analyzer.workflow;

import com.aijob.analyzer.workflow.domain.ErrorCategory;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.WorkflowError;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class DefaultFallbackPolicy implements FallbackPolicy {
    private static final Set<ErrorCategory> KNOWN_FINAL_ANALYSIS_FAILURES = EnumSet.of(
            ErrorCategory.TRANSPORT,
            ErrorCategory.PROVIDER
    );

    @Override
    public boolean allows(StageType failedStage, WorkflowError error) {
        Objects.requireNonNull(failedStage, "failedStage");
        Objects.requireNonNull(error, "error");
        return failedStage == StageType.LLM_ANALYSIS
                && error.retryable()
                && KNOWN_FINAL_ANALYSIS_FAILURES.contains(error.category());
    }
}

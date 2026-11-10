package com.aijob.analyzer.workflow.domain;

public record WorkflowError(
        String code,
        ErrorCategory category,
        boolean retryable,
        String safeMessage) {
}

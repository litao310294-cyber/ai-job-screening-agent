package com.aijob.analyzer.support.retry;

import com.aijob.analyzer.workflow.domain.WorkflowError;

public class OperationFailureException extends RuntimeException {
    private final WorkflowError error;

    public OperationFailureException(WorkflowError error) {
        super(error.safeMessage());
        this.error = error;
    }

    public WorkflowError error() {
        return error;
    }
}

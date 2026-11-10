package com.aijob.analyzer.support.retry;

import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceRecorder;

public interface RetryExecutor {
    <T> T execute(
            String operationName,
            RetryPolicy policy,
            ExecutionDeadline deadline,
            RetryableOperation<T> operation);

    default <T> T execute(String operationName, RetryPolicy policy, ExecutionDeadline deadline,
            TraceContext ignoredTrace, TraceRecorder ignoredRecorder, RetryableOperation<T> operation) {
        return execute(operationName, policy, deadline, operation);
    }
}

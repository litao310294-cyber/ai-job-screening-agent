package com.aijob.analyzer.support.retry;

import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.domain.ErrorCategory;
import com.aijob.analyzer.workflow.domain.WorkflowError;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public class DefaultRetryExecutor implements RetryExecutor {
    private final Sleeper sleeper;
    private final JitterSource jitterSource;

    public DefaultRetryExecutor(Sleeper sleeper, JitterSource jitterSource) {
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource");
    }

    @Override
    public <T> T execute(String operationName, RetryPolicy policy, ExecutionDeadline deadline,
                         RetryableOperation<T> operation) {
        return execute(operationName, policy, deadline, null, null, operation);
    }

    @Override
    public <T> T execute(String operationName, RetryPolicy policy, ExecutionDeadline deadline, TraceContext traceContext, TraceRecorder recorder, RetryableOperation<T> operation) {
        Objects.requireNonNull(operationName, "operationName");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(operation, "operation");

        int attempt = 1;
        Duration nextBackoff = policy.initialBackoff();

        while (true) {
            try {
                if (deadline != null && deadline.isExpired()) {
                    throw new OperationFailureException(new WorkflowError(
                            "DEADLINE_EXCEEDED",
                            ErrorCategory.INTERNAL,
                            false,
                            "Operation " + operationName + " exceeded deadline"
                    ));
                }

                return operation.execute(attempt);

            } catch (OperationFailureException e) {
                WorkflowError error = e.error();
                if (!error.retryable() || attempt >= policy.maxAttempts()) {
                    throw e;
                }

                if (recorder != null && traceContext != null) {
                    recorder.event(traceContext, TraceEventType.RETRY_SCHEDULED, Map.of(
                            "operation", operationName,
                            "attempt", attempt,
                            "nextAttempt", attempt + 1,
                            "error", error.code(),
                            "category", error.category().name()
                    ));
                }

                try {
                    long backoffMillis = calculateBackoff(nextBackoff, policy);
                    Duration backoff = Duration.ofMillis(backoffMillis);

                    if (deadline != null && deadline.remaining().compareTo(backoff) <= 0) {
                        throw new OperationFailureException(new WorkflowError(
                                "INSUFFICIENT_BUDGET",
                                ErrorCategory.INTERNAL,
                                false,
                                "Remaining budget insufficient for backoff"
                        ));
                    }

                    sleeper.sleep(backoff);

                    attempt++;
                    nextBackoff = nextBackoff(nextBackoff, policy);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new OperationFailureException(new WorkflowError(
                            "INTERRUPTED",
                            ErrorCategory.INTERNAL,
                            false,
                            "Operation " + operationName + " interrupted during backoff"
                    ));
                }
            }
        }
    }

    private long calculateBackoff(Duration backoff, RetryPolicy policy) {
        long baseMillis = Math.min(backoff.toMillis(), policy.maxBackoff().toMillis());
        double sample = Math.max(0.0, Math.min(Math.nextDown(1.0), jitterSource.next()));
        double factor = 1.0 + ((sample * 2.0) - 1.0) * policy.jitterRatio();
        double jitteredMillis = baseMillis * factor;
        if (jitteredMillis <= 0.0) {
            return 0L;
        }
        return Math.min(policy.maxBackoff().toMillis(), (long) jitteredMillis);
    }

    private Duration nextBackoff(Duration current, RetryPolicy policy) {
        long maxMillis = policy.maxBackoff().toMillis();
        double multipliedMillis = current.toMillis() * policy.multiplier();
        if (multipliedMillis >= maxMillis) {
            return policy.maxBackoff();
        }
        return Duration.ofMillis((long) multipliedMillis);
    }
}

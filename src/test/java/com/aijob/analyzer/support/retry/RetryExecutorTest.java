package com.aijob.analyzer.support.retry;

import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.support.time.TimeSource;
import com.aijob.analyzer.workflow.domain.ErrorCategory;
import com.aijob.analyzer.workflow.domain.WorkflowError;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RetryExecutorTest {

    private final RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(10), 2.0, Duration.ofMillis(100), 0.0);
    private final Sleeper instantSleeper = d -> {};
    private final JitterSource noJitter = () -> 0.5; // (0.5*2-1) = 0 jitter

    @Test
    void retryOnRetryableError() {
        var executor = new DefaultRetryExecutor(instantSleeper, noJitter);
        var attempts = new AtomicInteger(0);

        String result = executor.execute("test", policy, null, null, null, attempt -> {
            attempts.incrementAndGet();
            if (attempt < 3) {
                throw new OperationFailureException(new WorkflowError("RETRY", ErrorCategory.PROVIDER, true, "retry"));
            }
            return "success";
        });

        assertEquals("success", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void failOnNonRetryableError() {
        var executor = new DefaultRetryExecutor(instantSleeper, noJitter);
        var attempts = new AtomicInteger(0);

        assertThrows(OperationFailureException.class, () ->
            executor.execute("test", policy, null, null, null, attempt -> {
                attempts.incrementAndGet();
                throw new OperationFailureException(new WorkflowError("FATAL", ErrorCategory.BUSINESS, false, "fatal"));
            })
        );

        assertEquals(1, attempts.get());
    }

    @Test
    void failWhenAttemptsExhausted() {
        var executor = new DefaultRetryExecutor(instantSleeper, noJitter);
        var attempts = new AtomicInteger(0);

        assertThrows(OperationFailureException.class, () ->
            executor.execute("test", policy, null, null, null, attempt -> {
                attempts.incrementAndGet();
                throw new OperationFailureException(new WorkflowError("RETRY", ErrorCategory.PROVIDER, true, "retry"));
            })
        );

        assertEquals(3, attempts.get());
    }

    @Test
    void failOnDeadlineExceeded() {
        var executor = new DefaultRetryExecutor(instantSleeper, noJitter);
        var deadline = ExecutionDeadline.at(Instant.now().minusSeconds(1), TimeSource.SYSTEM);

        assertThrows(OperationFailureException.class, () ->
            executor.execute("test", policy, deadline, null, null, attempt -> "should not run")
        );
    }

    @Test
    void rejectsUnsafeRetryPolicyValues() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RetryPolicy(0, Duration.ZERO, 1.0, Duration.ZERO, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RetryPolicy(2, Duration.ofMillis(-1), 1.0, Duration.ZERO, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RetryPolicy(2, Duration.ZERO, 0.5, Duration.ZERO, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RetryPolicy(2, Duration.ZERO, Double.POSITIVE_INFINITY, Duration.ZERO, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RetryPolicy(2, Duration.ZERO, 1.0, Duration.ZERO, -0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RetryPolicy(2, Duration.ZERO, 1.0, Duration.ZERO, 1.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RetryPolicy(2, Duration.ofMillis(2), 1.0, Duration.ofMillis(1), 0.0))
        );
    }

    @Test
    void doesNotSleepWhenBackoffConsumesAllRemainingBudget() {
        var now = new AtomicLong();
        TimeSource timeSource = now::get;
        var sleepCalls = new AtomicInteger();
        Sleeper advancingSleeper = duration -> {
            sleepCalls.incrementAndGet();
            now.addAndGet(duration.toNanos());
        };
        var executor = new DefaultRetryExecutor(advancingSleeper, noJitter);
        var deadline = ExecutionDeadline.fromNow(Duration.ofMillis(10), timeSource);
        var attempts = new AtomicInteger();
        var exactBudgetPolicy = new RetryPolicy(
                2,
                Duration.ofMillis(10),
                1.0,
                Duration.ofMillis(10),
                0.0
        );

        var failure = assertThrows(OperationFailureException.class, () ->
                executor.execute("test", exactBudgetPolicy, deadline, null, null, attempt -> {
                    attempts.incrementAndGet();
                    throw new OperationFailureException(
                            new WorkflowError("TIMEOUT", ErrorCategory.PROVIDER, true, "timeout")
                    );
                })
        );

        assertEquals("INSUFFICIENT_BUDGET", failure.error().code());
        assertEquals(1, attempts.get());
        assertEquals(0, sleepCalls.get());
    }

    @Test
    void jitteredBackoffNeverExceedsConfiguredMaximum() {
        var slept = new java.util.ArrayList<Duration>();
        var executor = new DefaultRetryExecutor(slept::add, () -> Math.nextDown(1.0));
        var boundedPolicy = new RetryPolicy(
                2,
                Duration.ofMillis(100),
                2.0,
                Duration.ofMillis(100),
                1.0
        );

        assertThrows(OperationFailureException.class, () ->
                executor.execute("test", boundedPolicy, null, null, null, attempt -> {
                    throw new OperationFailureException(
                            new WorkflowError("TIMEOUT", ErrorCategory.PROVIDER, true, "timeout")
                    );
                })
        );

        assertEquals(List.of(Duration.ofMillis(100)), slept);
    }

    @Test
    void interruptionRestoresFlagAndStopsRetryChain() {
        var attempts = new AtomicInteger();
        var executor = new DefaultRetryExecutor(duration -> {
            throw new InterruptedException("stop");
        }, noJitter);

        try {
            var failure = assertThrows(OperationFailureException.class, () ->
                    executor.execute("test", policy, null, null, null, attempt -> {
                        attempts.incrementAndGet();
                        throw new OperationFailureException(
                                new WorkflowError("TIMEOUT", ErrorCategory.PROVIDER, true, "timeout")
                        );
                    })
            );

            assertEquals("INTERRUPTED", failure.error().code());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, attempts.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void deadlineUsesMonotonicTimeSourceForRemainingBudget() {
        var now = new AtomicLong(1_000L);
        var deadline = ExecutionDeadline.fromNow(Duration.ofNanos(500), now::get);

        now.addAndGet(200L);
        assertEquals(Duration.ofNanos(300), deadline.remaining());
        assertFalse(deadline.isExpired());

        now.addAndGet(300L);
        assertEquals(Duration.ZERO, deadline.remaining());
        assertTrue(deadline.isExpired());
    }
}

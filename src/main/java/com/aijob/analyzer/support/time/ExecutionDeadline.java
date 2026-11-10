package com.aijob.analyzer.support.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ExecutionDeadline {
    private final long startNano;
    private final long budgetNanos;
    private final TimeSource timeSource;

    private ExecutionDeadline(long startNano, long budgetNanos, TimeSource timeSource) {
        this.startNano = startNano;
        this.budgetNanos = budgetNanos;
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
    }

    public boolean isExpired() {
        return elapsedNanos() >= budgetNanos;
    }

    public Duration remaining() {
        long elapsed = elapsedNanos();
        if (elapsed >= budgetNanos) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(budgetNanos - elapsed);
    }

    public static ExecutionDeadline fromNow(Duration duration, TimeSource timeSource) {
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(timeSource, "timeSource");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        return new ExecutionDeadline(timeSource.nanoTime(), toNanosSaturated(duration), timeSource);
    }

    public static ExecutionDeadline at(Instant instant, TimeSource timeSource) {
        Objects.requireNonNull(instant, "instant");
        Duration remaining = Duration.between(Instant.now(), instant);
        return fromNow(remaining.isNegative() ? Duration.ZERO : remaining, timeSource);
    }

    private long elapsedNanos() {
        long elapsed = timeSource.nanoTime() - startNano;
        return Math.max(0L, elapsed);
    }

    private static long toNanosSaturated(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}

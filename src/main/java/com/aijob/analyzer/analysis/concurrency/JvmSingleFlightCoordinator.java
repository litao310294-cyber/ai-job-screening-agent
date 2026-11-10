package com.aijob.analyzer.analysis.concurrency;

import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecorder;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Same-JVM duplicate-compute suppression; durable uniqueness remains the correctness boundary. */
public final class JvmSingleFlightCoordinator<K> implements SingleFlightCoordinator<K> {
    private final ConcurrentHashMap<K, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(K key, Duration followerWaitTimeout, Supplier<T> leaderWork) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(followerWaitTimeout, "followerWaitTimeout");
        Objects.requireNonNull(leaderWork, "leaderWork");
        CompletableFuture<Object> created = new CompletableFuture<>();
        CompletableFuture<Object> shared = inFlight.putIfAbsent(key, created);
        if (shared == null) {
            try {
                T result = leaderWork.get();
                created.complete(result);
                return result;
            } catch (Throwable failure) {
                created.completeExceptionally(failure);
                throw rethrow(failure);
            } finally {
                inFlight.remove(key, created);
            }
        }
        try {
            return (T) shared.get(followerWaitTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new FollowerWaitTimeoutException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting for leader", e);
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        }
    }

    @Override
    public <T> T execute(K key, Duration followerWaitTimeout, TraceContext trace,
                         TraceRecorder recorder, Supplier<T> leaderWork) {
        if (trace == null || recorder == null) {
            return execute(key, followerWaitTimeout, leaderWork);
        }
        CompletableFuture<Object> created = new CompletableFuture<>();
        CompletableFuture<Object> shared = inFlight.putIfAbsent(key, created);
        if (shared == null) {
            recorder.event(trace, TraceEventType.SINGLEFLIGHT_LEADER, Map.of("key", String.valueOf(key)));
            try {
                T result = leaderWork.get();
                created.complete(result);
                return result;
            } catch (Throwable failure) {
                created.completeExceptionally(failure);
                throw rethrow(failure);
            } finally {
                inFlight.remove(key, created);
            }
        }
        recorder.event(trace, TraceEventType.SINGLEFLIGHT_FOLLOWER, Map.of("key", String.valueOf(key)));
        try {
            @SuppressWarnings("unchecked") T result = (T) shared.get(followerWaitTimeout.toNanos(), TimeUnit.NANOSECONDS);
            return result;
        } catch (TimeoutException e) {
            throw new FollowerWaitTimeoutException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting for leader", e);
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        }
    }

    int inFlightCount() {
        return inFlight.size();
    }

    private RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(failure);
    }
}

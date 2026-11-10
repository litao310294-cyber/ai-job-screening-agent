package com.aijob.analyzer.analysis.concurrency;


import java.time.Duration;
import java.util.function.Supplier;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceRecorder;

public interface SingleFlightCoordinator<K> {
    <T> T execute(K key, Duration followerWaitTimeout, Supplier<T> leaderWork);

    default <T> T execute(K key, Duration followerWaitTimeout, TraceContext ignoredTrace,
            TraceRecorder ignoredRecorder, Supplier<T> leaderWork) {
        return execute(key, followerWaitTimeout, leaderWork);
    }
}

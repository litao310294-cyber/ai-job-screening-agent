package com.aijob.analyzer.analysis.concurrency;

import com.aijob.analyzer.support.InMemoryTraceRecorder;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SingleFlightTraceTest {
    @Test
    void leader_and_follower_are_observable_without_duplicate_compute() throws Exception {
        JvmSingleFlightCoordinator<String> coordinator = new JvmSingleFlightCoordinator<>();
        InMemoryTraceRecorder leaderTrace = new InMemoryTraceRecorder();
        InMemoryTraceRecorder followerTrace = new InMemoryTraceRecorder();
        TraceContext leaderContext = new TraceContext("leader-trace", "task");
        TraceContext followerContext = new TraceContext("follower-trace", "task");
        AtomicInteger computes = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread leader = Thread.startVirtualThread(() -> coordinator.execute("same", Duration.ofSeconds(2), leaderContext, leaderTrace, () -> {
            computes.incrementAndGet(); entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "artifact";
        }));
        entered.await();
        while (coordinator.inFlightCount() != 1) {
            Thread.onSpinWait();
        }
        var followerResult = new java.util.concurrent.CompletableFuture<String>();
        Thread followerThread = Thread.startVirtualThread(() -> followerResult.complete(coordinator.execute("same", Duration.ofSeconds(2), followerContext, followerTrace, () -> {
            computes.incrementAndGet(); return "wrong";
        })));
        while (followerTrace.getEvents().isEmpty()) {
            Thread.onSpinWait();
        }
        release.countDown();
        leader.join();
        followerThread.join();

        assertEquals("artifact", followerResult.join());
        assertEquals(1, computes.get());
        assertEquals(TraceEventType.SINGLEFLIGHT_LEADER, leaderTrace.getEvents().getFirst().type());
        assertEquals(TraceEventType.SINGLEFLIGHT_FOLLOWER, followerTrace.getEvents().getFirst().type());
    }
}

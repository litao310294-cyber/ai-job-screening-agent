package com.aijob.analyzer.analysis.concurrency;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JvmSingleFlightCoordinatorTest {

    @Test
    void twentySameKeyRequestsShareOneLeader() throws Exception {
        JvmSingleFlightCoordinator<String> coordinator = new JvmSingleFlightCoordinator<>();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch arrived = new CountDownLatch(20);
        CountDownLatch leaderEntered = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            List<Future<String>> futures = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        arrived.countDown();
                        return coordinator.execute("same", Duration.ofSeconds(2), null, null, () -> {
                            calls.incrementAndGet();
                            leaderEntered.countDown();
                            java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(200).toNanos());
                            return "artifact";
                        });
                    }))
                    .toList();
            start.countDown();
            leaderEntered.await();
            arrived.await();
            for (Future<String> future : futures) assertEquals("artifact", future.get());
        }
        assertEquals(1, calls.get());
        assertEquals(0, coordinator.inFlightCount());
    }

    @Test
    void leaderFailureIsSharedAndCleanupAllowsANewLeader() {
        JvmSingleFlightCoordinator<String> coordinator = new JvmSingleFlightCoordinator<>();
        assertThrows(IllegalStateException.class, () -> coordinator.execute(
                "same", Duration.ofSeconds(1), null, null, () -> { throw new IllegalStateException("boom"); }
        ));
        assertEquals(0, coordinator.inFlightCount());
        assertEquals("recovered", coordinator.execute("same", Duration.ofSeconds(1), null, null, () -> "recovered"));
    }

    @Test
    void followerTimeoutDoesNotCancelLeader() throws Exception {
        JvmSingleFlightCoordinator<String> coordinator = new JvmSingleFlightCoordinator<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> leader = pool.submit(() -> coordinator.execute("same", Duration.ofSeconds(1), null, null, () -> {
                entered.countDown();
                awaitUnchecked(release);
                return "artifact";
            }));
            entered.await();
            assertThrows(FollowerWaitTimeoutException.class, () -> coordinator.execute(
                    "same", Duration.ofMillis(10), null, null, () -> "must-not-run"
            ));
            release.countDown();
            assertEquals("artifact", leader.get());
        }
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

}

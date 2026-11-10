package com.aijob.analyzer.cache;

import com.aijob.analyzer.cache.model.CacheAccessStatus;
import com.aijob.analyzer.cache.model.CacheLookupOutcome;
import com.aijob.analyzer.cache.model.CacheReadResult;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.support.InMemoryTraceRecorder;
import com.aijob.analyzer.trace.TraceContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CacheAsideResolverTest {

    private final CacheAsideResolver<String> resolver = new CacheAsideResolver<>();

    @Test
    void redisHitSkipsDurableReadAndCompute() {
        AtomicInteger db = new AtomicInteger();
        AtomicInteger compute = new AtomicInteger();

        String result = resolver.resolve(
                null,
                null,
                () -> new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.HIT, "cached"),
                () -> { db.incrementAndGet(); return new DbReadResult<>(DbReadOutcome.FOUND, "db", null); },
                () -> { compute.incrementAndGet(); return "computed"; },
                ignored -> { throw new AssertionError("cache refill must not run"); }
        );

        assertEquals("cached", result);
        assertEquals(0, db.get());
        assertEquals(0, compute.get());
    }

    @Test
    void cacheMissAndDbFoundReturnsDurableArtifactAndRefillsBestEffort() {
        AtomicInteger compute = new AtomicInteger();
        AtomicInteger refill = new AtomicInteger();

        String result = resolver.resolve(
                null,
                null,
                () -> new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null),
                () -> new DbReadResult<>(DbReadOutcome.FOUND, "durable", null),
                () -> { compute.incrementAndGet(); return "computed"; },
                ignored -> { refill.incrementAndGet(); throw new RuntimeException("redis down"); }
        );

        assertEquals("durable", result);
        assertEquals(0, compute.get());
        assertEquals(1, refill.get());
    }

    @Test
    void unavailableCacheStillReadsDbAndNeverTreatsItAsMiss() {
        AtomicInteger compute = new AtomicInteger();

        String result = resolver.resolve(
                null,
                null,
                () -> new CacheReadResult<>(CacheAccessStatus.UNAVAILABLE, null, null),
                () -> new DbReadResult<>(DbReadOutcome.FOUND, "durable", null),
                () -> { compute.incrementAndGet(); return "computed"; },
                ignored -> { }
        );

        assertEquals("durable", result);
        assertEquals(0, compute.get());
    }

    @Test
    void unavailableCacheWithTraceRecorderStillReadsDb() {
        InMemoryTraceRecorder recorder = new InMemoryTraceRecorder();
        String result = resolver.resolve(
                new TraceContext("trace-cache", "task-cache"),
                recorder,
                () -> new CacheReadResult<>(CacheAccessStatus.UNAVAILABLE, null, null),
                () -> new DbReadResult<>(DbReadOutcome.FOUND, "durable", null),
                () -> "computed",
                ignored -> { }
        );

        assertEquals("durable", result);
        assertEquals("UNAVAILABLE", recorder.getEvents().getFirst().attributes().get("status"));
        assertEquals("null", recorder.getEvents().getFirst().attributes().get("outcome"));
    }

    @Test
    void onlyDbNotFoundPermitsCompute() {
        AtomicInteger compute = new AtomicInteger();

        String result = resolver.resolve(
                null,
                null,
                () -> new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.INVALID, null),
                () -> new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null),
                () -> { compute.incrementAndGet(); return "computed"; },
                ignored -> { }
        );

        assertEquals("computed", result);
        assertEquals(1, compute.get());
    }

    @Test
    void dbReadFailedForbidsCompute() {
        AtomicInteger compute = new AtomicInteger();

        assertThrows(CacheAsideResolver.DurableReadFailedException.class, () -> resolver.resolve(
                null,
                null,
                () -> new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null),
                () -> new DbReadResult<>(DbReadOutcome.READ_FAILED, null, "MYSQL_READ_FAILED"),
                () -> { compute.incrementAndGet(); return "computed"; },
                ignored -> { }
        ));
        assertEquals(0, compute.get());
    }

    @Test
    void computedDurableResultRefillsCacheWithoutLettingWriteFailureChangeSuccess() {
        AtomicInteger refill = new AtomicInteger();
        String result = resolver.resolve(null, null,
                () -> new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null),
                () -> new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null),
                () -> "committed",
                value -> { assertEquals("committed", value); refill.incrementAndGet(); throw new RuntimeException("redis unavailable"); });
        assertEquals("committed", result);
        assertEquals(1, refill.get());
    }
}

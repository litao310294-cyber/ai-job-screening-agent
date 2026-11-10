package com.aijob.analyzer.analysis;

import com.aijob.analyzer.analysis.concurrency.JvmSingleFlightCoordinator;
import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.cache.model.*;
import com.aijob.analyzer.cache.port.ArtifactCache;
import com.aijob.analyzer.persistence.model.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CanonicalAnalysisResolverTest {
    @Test
    void hitSkipsDbAndCompute() {
        ArtifactCache<AnalysisArtifact> cache = mock(ArtifactCache.class);
        AnalysisResultRepository db = mock(AnalysisResultRepository.class);
        AnalysisArtifact artifact = mock(AnalysisArtifact.class);
        when(cache.get("k")).thenReturn(new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.HIT, artifact));
        assertSame(artifact, new CanonicalAnalysisResolver(cache, db, new JvmSingleFlightCoordinator<>(), Duration.ofMinutes(1)).resolve("k", mock(AnalysisIdentity.class), Duration.ofSeconds(1), null, null, () -> {
            throw new AssertionError();
        }));
        verifyNoInteractions(db);
    }

    @Test
    void readFailedForbidsCompute() {
        ArtifactCache<AnalysisArtifact> cache = mock(ArtifactCache.class);
        AnalysisResultRepository db = mock(AnalysisResultRepository.class);
        AnalysisIdentity id = mock(AnalysisIdentity.class);
        when(cache.get("k")).thenReturn(new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null));
        when(db.findCanonical(id)).thenReturn(new DbReadResult<>(DbReadOutcome.READ_FAILED, null, "READ_FAILED"));
        AtomicInteger compute = new AtomicInteger();
        assertThrows(CanonicalAnalysisResolver.DurableAnalysisReadFailedException.class, () -> new CanonicalAnalysisResolver(cache, db, new JvmSingleFlightCoordinator<>(), Duration.ofMinutes(1)).resolve("k", id, Duration.ofSeconds(1), null, null, () -> {
            compute.incrementAndGet();
            return mock(AnalysisArtifact.class);
        }));
        assertEquals(0, compute.get());
    }

    @Test
    void notFoundComputesOnceAndLeaderDoubleChecks() {
        ArtifactCache<AnalysisArtifact> cache = mock(ArtifactCache.class);
        AnalysisResultRepository db = mock(AnalysisResultRepository.class);
        AnalysisIdentity id = mock(AnalysisIdentity.class);
        when(cache.get("k")).thenReturn(new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null));
        when(db.findCanonical(id)).thenReturn(new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null));
        AnalysisArtifact computed = mock(AnalysisArtifact.class);
        AtomicInteger compute = new AtomicInteger();
        assertSame(computed, new CanonicalAnalysisResolver(cache, db, new JvmSingleFlightCoordinator<>(), Duration.ofMinutes(1)).resolve("k", id, Duration.ofSeconds(1), null, null, () -> {
            compute.incrementAndGet();
            return computed;
        }));
        assertEquals(1, compute.get());
        verify(db, times(2)).findCanonical(id);
    }

    @Test
    void dbSuccessAndRedisSetFailDoesNotImpactBusinessSuccess() {
        ArtifactCache<AnalysisArtifact> cache = mock(ArtifactCache.class);
        AnalysisResultRepository db = mock(AnalysisResultRepository.class);
        AnalysisIdentity id = mock(AnalysisIdentity.class);
        AnalysisArtifact artifact = mock(AnalysisArtifact.class);
        when(cache.get("k")).thenReturn(new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null));
        when(db.findCanonical(id)).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, artifact, null));
        doThrow(new RuntimeException("redis down")).when(cache).put(anyString(), any(), any());
        assertDoesNotThrow(() -> {
            AnalysisArtifact resolved = new CanonicalAnalysisResolver(cache, db, new JvmSingleFlightCoordinator<>(), Duration.ofMinutes(1)).resolve("k", id, Duration.ofSeconds(1), null, null, () -> {
                throw new AssertionError("should not compute");
            });
            assertSame(artifact, resolved);
        });
    }
}

package com.aijob.analyzer.analysis;

import com.aijob.analyzer.analysis.concurrency.SingleFlightCoordinator;
import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.cache.model.CacheLookupOutcome;
import com.aijob.analyzer.cache.model.CacheReadResult;
import com.aijob.analyzer.cache.port.ArtifactCache;
import com.aijob.analyzer.persistence.model.DbReadOutcome;

import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceRecorder;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Canonical analysis preflight; leaderWork must double-check DB before compute. */
public final class CanonicalAnalysisResolver {
    private final ArtifactCache<AnalysisArtifact> cache;
    private final AnalysisResultRepository repository;
    private final SingleFlightCoordinator<AnalysisIdentity> singleFlight;
    private final Duration cacheTtl;
    public CanonicalAnalysisResolver(ArtifactCache<AnalysisArtifact> cache, AnalysisResultRepository repository, SingleFlightCoordinator<AnalysisIdentity> singleFlight, Duration cacheTtl) { this.cache=cache;this.repository=repository;this.singleFlight=singleFlight;this.cacheTtl=cacheTtl; }
    public AnalysisArtifact resolve(String cacheKey, AnalysisIdentity identity, Duration followerTimeout, TraceContext trace, TraceRecorder recorder, Supplier<AnalysisArtifact> computeAndCommit) {
        CacheReadResult<AnalysisArtifact> cached=cache.get(cacheKey);
        if(cached.lookupOutcome()==CacheLookupOutcome.HIT) return cached.value();
        AnalysisArtifact existing=readDurableOrNull(identity);
        if(existing!=null){bestEffortPut(cacheKey,existing);return existing;}
        return singleFlight.execute(identity,followerTimeout,trace,recorder,()->{
            AnalysisArtifact doubleChecked=readDurableOrNull(identity);
            if(doubleChecked!=null){bestEffortPut(cacheKey,doubleChecked);return doubleChecked;}
            AnalysisArtifact committed=Objects.requireNonNull(computeAndCommit.get(),"computed artifact");
            bestEffortPut(cacheKey,committed);
            return committed;
        });
    }
    private AnalysisArtifact readDurableOrNull(AnalysisIdentity identity){var read=repository.findCanonical(identity);if(read.outcome()==DbReadOutcome.FOUND)return read.value();if(read.outcome()==DbReadOutcome.NOT_FOUND)return null;throw new DurableAnalysisReadFailedException(read.errorCode());}
    private void bestEffortPut(String key,AnalysisArtifact artifact){try{cache.put(key,artifact,cacheTtl);}catch(RuntimeException ignored){}}
    public static final class DurableAnalysisReadFailedException extends RuntimeException{public DurableAnalysisReadFailedException(String code){super(code);}}
}

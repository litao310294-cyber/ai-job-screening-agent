package com.aijob.analyzer.cache.port;

import com.aijob.analyzer.cache.model.CacheReadResult;
import java.time.Duration;

public interface ArtifactCache<T> {
    CacheReadResult<T> get(String cacheKey);

    void put(String cacheKey, T value, Duration ttl);
}

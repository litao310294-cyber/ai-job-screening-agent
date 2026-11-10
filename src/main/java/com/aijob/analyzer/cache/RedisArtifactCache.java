package com.aijob.analyzer.cache;

import com.aijob.analyzer.cache.model.CacheAccessStatus;
import com.aijob.analyzer.cache.model.CacheLookupOutcome;
import com.aijob.analyzer.cache.model.CacheReadResult;
import com.aijob.analyzer.cache.port.ArtifactCache;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;

public final class RedisArtifactCache<T> implements ArtifactCache<T> {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final JavaType targetType;

    public RedisArtifactCache(StringRedisTemplate redis, ObjectMapper objectMapper, JavaType targetType) {
        this.redis = Objects.requireNonNull(redis);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.targetType = Objects.requireNonNull(targetType);
    }

    @Override
    public CacheReadResult<T> get(String cacheKey) {
        try {
            String json = redis.opsForValue().get(cacheKey);
            if (json == null) return new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null);
            try {
                return new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.HIT, objectMapper.readValue(json, targetType));
            } catch (Exception invalidJson) {
                return new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.INVALID, null);
            }
        } catch (QueryTimeoutException timeout) {
            return new CacheReadResult<>(CacheAccessStatus.TIMEOUT, null, null);
        } catch (RedisConnectionFailureException unavailable) {
            return new CacheReadResult<>(CacheAccessStatus.UNAVAILABLE, null, null);
        } catch (RuntimeException unavailable) {
            return new CacheReadResult<>(CacheAccessStatus.UNAVAILABLE, null, null);
        }
    }

    @Override
    public void put(String cacheKey, T value, Duration ttl) {
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception failure) {
            throw new RedisWriteException(failure);
        }
    }

    public static final class RedisWriteException extends RuntimeException {
        RedisWriteException(Throwable cause) { super(cause); }
    }
}
